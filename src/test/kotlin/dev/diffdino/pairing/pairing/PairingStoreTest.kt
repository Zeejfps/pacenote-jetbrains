package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.DraftOutcome
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftSpan
import dev.diffdino.pairing.model.DraftState
import dev.diffdino.pairing.model.EditorCaret
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.LineSpan
import dev.diffdino.pairing.model.Milestone
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.model.RoadmapChange
import dev.diffdino.pairing.model.RoadmapEntry
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopMiss
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.TextPosition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing loop's hand-off: one open stop at a time, Done handing the agent the user's diff
 * since the stop was shown, and every move going to the agent in the order it was made, with
 * nothing waiting on the user in between. */
class PairingStoreTest {
    private val ui = UiThread()
    private val presentation = RecordingPairingPresentation()
    private val workspace = ScriptedWorkspace()
    private val transcript = AgentTranscript()
    private val moves = mutableListOf<PairingAction>()
    private val store = PairingStore("Add a retry", "Claude Code", transcript, presentation, workspace, ui.scope) { moves.add(it) }

    init {
        ui.run { store.markRunning() }
    }

    @After
    fun tearDown() {
        ui.run { store.dispose() }
        ui.close()
    }

    private val draft = DraftRequest("public void Fetch() => Retry(Send);", DraftSpan.Declaration)

    private suspend fun open(symbol: String = "Fetch", replace: Boolean = false, request: DraftRequest = draft): OpenStop {
        val opening = store.openStop(StopTarget("src/Client.cs", symbol, null), "Retry $symbol", "Because.", request, replace)
        return (opening as? StopOpening.Opened ?: throw AssertionError("Expected the stop to open, got $opening")).stop
    }

    private fun refusal(opening: StopOpening): String =
        (opening as? StopOpening.Refused ?: throw AssertionError("Expected a refusal, got $opening")).message

    private inline fun <reified T> single(items: List<*>): T {
        assertEquals("one item expected in $items", 1, items.size)
        return items.single() as? T ?: throw AssertionError("Expected ${T::class.simpleName}, got ${items.single()}")
    }

    @Test
    fun openStop_placesItAndTheAgentsCodeInTheEditor_andNumbersIt() = ui.run {
        val stop = open()

        assertEquals(1, stop.stop.number)
        assertEquals(listOf("show src/Client.cs#Fetch", "reveal draft", "show draft"), presentation.calls)
        assertSame(stop, store.stop.value)
        assertEquals(DraftPlace.Replace(LineSpan(10, 14)), presentation.drafts.single().place)
    }

    @Test
    fun theAgentsCode_goesOnTheLinesItNames() = ui.run {
        val stop = open(request = DraftRequest("retry();", DraftSpan.After(12)))

        assertEquals(StopDraft("retry();", DraftPlace.InsertAfter(FileLine(12))), stop.draft)
    }

    @Test
    fun theAgentsCode_onLinesTheFileDoesNotHave_isRefused() = ui.run {
        val opening = store.openStop(StopTarget("src/Client.cs", "Fetch", null), "t", "r", DraftRequest("x", DraftSpan.Lines(38, 45)), false)

        assertTrue(refusal(opening).contains("40 lines"))
        assertNull(store.stop.value)
    }

    @Test
    fun aSecondStop_isRefusedWhileOneIsOpen() = ui.run {
        open()

        val second = store.openStop(StopTarget("src/Client.cs", "Send", null), "t", "r", draft, false)

        assertTrue(refusal(second).contains("still open"))
        assertEquals(1, store.stop.value?.stop?.number)
    }

    @Test
    fun aSecondStop_thatReplaces_takesThePlaceOfTheFirst() = ui.run {
        open()

        val second = open("Send", replace = true)

        assertEquals(2, second.stop.number)
        assertSame(second, store.stop.value)
    }

    @Test
    fun aStopForAMissingSymbol_isRefusedWithWhatIsDeclared() = ui.run {
        presentation.answer = { StopPlacement.Missed(StopMiss.NoSuchSymbol(it.path, it.symbol, listOf("Client", "Client.Send"))) }

        val opening = store.openStop(StopTarget("src/Client.cs", "Nope", null), "t", "r", draft, false)

        assertTrue(refusal(opening).contains("Client.Send"))
        assertNull(store.stop.value)
    }

    @Test
    fun done_returnsTheDiffSinceTheStopWasShown_andClosesTheStop() = ui.run {
        workspace.current = "before"
        open()
        workspace.current = "after"
        workspace.diffs["before" to "after"] = "+ retry"

        store.done()

        val action = single<PairingAction.Done>(moves)
        assertEquals(1, action.stop)
        assertEquals("+ retry", action.diff)
        assertTrue("save" in presentation.calls)
        assertNull(store.stop.value)
    }

    @Test
    fun moves_goOutInOrder_soAMessageNeverHidesADone() = ui.run {
        open()
        store.done()
        store.say("Why a loop?")

        assertEquals(2, moves.size)
        assertTrue(moves[0] is PairingAction.Done)
        assertEquals("Why a loop?", (moves[1] as PairingAction.Message).text)
    }

    @Test
    fun say_carriesTheCaret() = ui.run {
        open()
        presentation.caretState.value = EditorCaret("C:/repo/src/Client.cs", TextPosition.at(12, 3), "retries")

        store.say("Is this the right place?")

        val ask = single<PairingAction.Message>(moves)
        assertEquals("retries", ask.caret?.selectedText)
        assertTrue(transcript.messages.value.any { it is PairingMessage.FromUser && it.text == "Is this the right place?" })
    }

    @Test
    fun aTurnEnding_handsTheMoveToTheUser_andTheirMoveHandsItBack() = ui.run {
        assertEquals(PairingPhase.Running(false), store.phase.value)
        store.markTurnStarted()
        store.markTurnEnded()
        assertEquals(PairingPhase.Running(true), store.phase.value)

        store.say("?")

        assertEquals(PairingPhase.Running(false), store.phase.value)
    }

    @Test
    fun whileATurnRuns_aStopDoesNotHandOverUntilTheTurnEnds() = ui.run {
        store.markTurnStarted()

        open()

        assertEquals(PairingPhase.Running(false), store.phase.value)
        assertTrue(store.hasHandedOver)
    }

    @Test
    fun anAgentWhoseTurnsAreNotSeen_handsOverWithAStopOrAReply() = ui.run {
        open()
        assertEquals(PairingPhase.Running(true), store.phase.value)

        store.skip()
        assertEquals(PairingPhase.Running(false), store.phase.value)

        store.addReply("Fair enough.")
        assertEquals(PairingPhase.Running(true), store.phase.value)
    }

    @Test
    fun aTurnThatLeavesNeitherAStopNorAReply_hasNotHandedOver() = ui.run {
        store.markTurnStarted()
        store.setRoadmap(listOf(Milestone("Model", false)))
        store.markTurnEnded()
        assertFalse(store.hasHandedOver)

        store.markTurnStarted()
        store.addReply("Where should the retry live?")

        assertTrue(store.hasHandedOver)
    }

    @Test
    fun endByUser_tellsTheAgentOnce() = ui.run {
        open()

        store.endByUser()
        store.endByUser()

        single<PairingAction.Ended>(moves)
        assertTrue(store.phase.value is PairingPhase.Ended)
        assertNull(store.stop.value)
    }

    @Test
    fun skip_closesTheStopWithoutADiff() = ui.run {
        open()

        store.skip()

        single<PairingAction.Skipped>(moves)
        assertNull(store.stop.value)
        assertEquals(1, workspace.captures)
    }

    @Test
    fun accept_putsTheAgentsCodeIn_andStaysOnTheStop() = ui.run {
        open()

        assertTrue(store.accept())

        assertTrue(moves.isEmpty())
        assertTrue(store.stop.value?.draftState is DraftState.Taken)
        assertTrue("take draft" in presentation.calls)
        assertTrue("clear draft" in presentation.calls)
    }

    @Test
    fun acceptAndNext_putsTheCodeIn_andMovesOn_asAccepted() = ui.run {
        workspace.current = "before"
        open()
        presentation.calls.clear()

        workspace.current = "after"
        store.acceptAndNext()

        val done = single<PairingAction.Done>(moves)
        assertEquals(DraftOutcome.AcceptedAsIs, done.draft)
        assertEquals("diff before..after", done.diff)
        assertTrue(presentation.calls.indexOf("take draft") < presentation.calls.indexOf("save"))
        assertNull(store.stop.value)
    }

    @Test
    fun acceptedCodeChangedBeforeNext_reachesTheAgentAsEdited() = ui.run {
        open()
        store.accept()
        presentation.fileText = "file, changed by hand"

        store.done()

        assertEquals(DraftOutcome.AcceptedThenEdited, single<PairingAction.Done>(moves).draft)
    }

    @Test
    fun theEditorsPills_acceptOrAcceptAndMoveOn() = ui.run {
        open()

        presentation.actions!!.acceptAndNext()
        waitFor("the pill to reach the agent") { moves.isNotEmpty() }

        assertEquals(DraftOutcome.AcceptedAsIs, single<PairingAction.Done>(moves).draft)
    }

    @Test
    fun theEditorsNextPill_movesOnWithoutAccepting() = ui.run {
        open()

        presentation.actions!!.next()
        waitFor("the pill to reach the agent") { moves.isNotEmpty() }

        assertEquals(DraftOutcome.NotAccepted, single<PairingAction.Done>(moves).draft)
    }

    @Test
    fun accept_thatCannotPutTheCodeIn_leavesTheStopOpen() = ui.run {
        open()
        presentation.takeSucceeds = false

        store.accept()

        assertTrue(moves.isEmpty())
        assertNotNull(store.stop.value)
        assertEquals(StopActivity.Idle, store.activity.value)
        assertEquals(NoticeTone.Error, single<PairingMessage.Notice>(transcript.messages.value).tone)
    }

    @Test
    fun aNewFileStop_createsTheFileEmpty_andItsCodeIsTheFirstBlock() = ui.run {
        presentation.answer = { StopPlacement.Placed(StopLocation.NewFile("C:/repo/" + it.path)) }

        val stop = open(request = DraftRequest("class Retry {}", DraftSpan.Declaration))

        assertEquals("", workspace.files["src/Client.cs"])
        assertEquals(DraftPlace.Replace(LineSpan(1, 1)), stop.draft.place)
        assertTrue("show draft" in presentation.calls)
    }

    @Test
    fun aNewFile_leftEmpty_goesWhenTheStopIsSkipped() = ui.run {
        presentation.answer = { StopPlacement.Placed(StopLocation.NewFile("C:/repo/" + it.path)) }
        open(request = DraftRequest("class Retry {}", DraftSpan.Declaration))

        store.skip()

        assertFalse(workspace.files.containsKey("src/Client.cs"))
    }

    @Test
    fun anEmptyFileThatWasAlreadyThere_staysWhenTheStopIsSkipped() = ui.run {
        workspace.files["src/Client.cs"] = ""
        presentation.answer = { StopPlacement.Placed(StopLocation.NewFile("C:/repo/" + it.path)) }
        open(request = DraftRequest("class Retry {}", DraftSpan.Declaration))

        store.skip()

        assertTrue(workspace.files.containsKey("src/Client.cs"))
    }

    @Test
    fun done_withoutAccepting_saysTheUserTypedIt() = ui.run {
        open()

        store.done()

        assertEquals(DraftOutcome.NotAccepted, single<PairingAction.Done>(moves).draft)
        assertTrue("clear draft" in presentation.calls)
    }

    @Test
    fun narration_streamsOntoOneMessagePerTurn() = ui.run {
        transcript.appendNarration("  Let's ")
        transcript.appendNarration("start.")
        transcript.closeNarration()
        transcript.appendNarration("Next.")

        val narrations = transcript.messages.value.filterIsInstance<PairingMessage.Narration>().map { it.text.value }
        assertEquals(listOf("Let's start.", "Next."), narrations)
    }

    @Test
    fun roadmap_marksWhatARevisionAddedAndDropped() = ui.run {
        store.setRoadmap(listOf(Milestone("Model", false), Milestone("Store", false)))
        assertTrue(store.roadmap.value.all { it.change == RoadmapChange.Kept })

        store.setRoadmap(listOf(Milestone("Model", true), Milestone("Events", false)))

        assertEquals(
            listOf(
                RoadmapEntry("Model", true, RoadmapChange.Kept),
                RoadmapEntry("Events", false, RoadmapChange.Added),
                RoadmapEntry("Store", false, RoadmapChange.Removed),
            ),
            store.roadmap.value,
        )
    }

    @Test
    fun whatTheUserSaysBeforeDone_reachesTheAgentAheadOfTheDiff() = ui.run {
        open()
        store.say("I went with an event instead of a callback")
        store.done()

        assertEquals(2, moves.size)
        assertEquals("I went with an event instead of a callback", (moves[0] as PairingAction.Message).text)
        assertTrue(moves[1] is PairingAction.Done)
    }

    @Test
    fun movingOnFromAStop_keepsTheConversation() = ui.run {
        open()
        store.say("Why here?")
        transcript.appendNarration("Because it's the entry point.")

        store.done()

        assertTrue(transcript.messages.value.any { it is PairingMessage.FromUser && it.text == "Why here?" })
        assertTrue(transcript.messages.value.any { it is PairingMessage.Narration && it.text.value == "Because it's the entry point." })
    }

    @Test
    fun skip_keepsTheConversation() = ui.run {
        open()
        store.say("Skip this one")

        store.skip()

        assertTrue(transcript.messages.value.any { it is PairingMessage.FromUser && it.text == "Skip this one" })
    }
}
