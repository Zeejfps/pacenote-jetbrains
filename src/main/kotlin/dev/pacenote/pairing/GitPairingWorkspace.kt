package dev.pacenote.pairing

import dev.pacenote.acp.AcpEnvironment
import dev.pacenote.model.FileCreation
import dev.pacenote.model.PairingWorkspace
import dev.pacenote.model.SnapshotResult
import dev.pacenote.model.TreeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A project's working tree for the loop: snapshots through git, and the empty files new-file stops
 * start from. The work happens on the IO dispatcher. */
class GitPairingWorkspace(private val root: Path, private val snapshots: WorkingTreeSnapshots) : PairingWorkspace {
    override suspend fun capture(): SnapshotResult<TreeSnapshot> = withContext(Dispatchers.IO) { snapshots.capture(root) }

    override suspend fun diff(from: TreeSnapshot, to: TreeSnapshot): SnapshotResult<String> =
        withContext(Dispatchers.IO) { snapshots.diff(root, from, to) }

    override suspend fun ensureEmptyFile(relativePath: String): FileCreation = withContext(Dispatchers.IO) {
        val path = absolute(relativePath) ?: return@withContext FileCreation.Refused("$relativePath is outside the repository.")
        try {
            if (Files.exists(path)) {
                if (isEmpty(path)) FileCreation.AlreadyEmpty
                else FileCreation.Refused("$relativePath has content now. Name a declaration in it.")
            } else {
                path.parent?.let { Files.createDirectories(it) }
                Files.createFile(path)
                FileCreation.Created
            }
        } catch (e: FileAlreadyExistsException) {
            FileCreation.Refused("$relativePath could not be created: ${e.message}")
        } catch (e: IOException) {
            FileCreation.Refused("$relativePath could not be created: ${e.message}")
        }
    }

    override suspend fun removeIfEmpty(relativePath: String) = withContext(Dispatchers.IO) {
        val path = absolute(relativePath) ?: return@withContext
        try {
            if (Files.isRegularFile(path) && isEmpty(path)) Files.delete(path)
        } catch (_: IOException) {
        }
    }

    private fun isEmpty(path: Path): Boolean = try {
        Files.readString(path).isBlank()
    } catch (_: MalformedInputException) {
        false
    }

    private fun absolute(relative: String): Path? = try {
        val base = root.toAbsolutePath().normalize()
        val full = base.resolve(relative).normalize()
        val inside = if (AcpEnvironment.isWindows) full.toString().lowercase().startsWith(base.toString().lowercase() + java.io.File.separator)
        else full.startsWith(base) && full != base
        if (inside) full else null
    } catch (_: InvalidPathException) {
        null
    }
}
