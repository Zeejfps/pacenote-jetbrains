# Pacenote AI

Pair with your coding agent, stop by stop, in your JetBrains IDE.

Most AI tools hand you a finished diff to review. Pacenote takes you through a change the way a rally
co-driver reads pace notes: one bend at a time. The agent lays out a roadmap, then stops at each place
in your code that needs to change. At each stop it drafts the code where it goes and says why, and
you accept it, write it yourself, or skip to the next stop.

Pacenote works with the agent you already use: **Claude Code**, **Codex** or **Gemini CLI**. It
talks to them over the [Agent Client Protocol](https://agentclientprotocol.com).

## Features

- **Pairing sessions.** Describe a goal and the agent walks you through it: a roadmap of milestones,
  then one stop at a time, each with its draft shown in the editor, coloured like the code around it.
  You accept, edit or skip it, and the agent sees what you actually wrote before the next stop.
- **Agent chat.** Ask about your code in the Pacenote tool window. Select code in the editor and it
  goes along with your next message.
- **Approvals.** Edits and other actions the agent wants to take come to you as cards showing what
  would change. You can allow one edit, every edit to the same file, or every tool of an MCP server,
  for as long as the agent runs.
- **Live activity.** While the agent works you see what it is doing (the command it runs, the file
  it reads) and for how long, with a Stop button to end its turn.
- **Agent presets.** Name the ways you run your agents, with their own permissions and extra
  arguments, under **Settings → Tools → Pacenote**.

## Requirements

- A JetBrains IDE, version **2026.2** or later. Pacenote depends only on the platform and Git, so it
  runs in IntelliJ IDEA, WebStorm, Rider, PyCharm and the rest. It is tested in WebStorm and Rider.
- **Git**: pairing sessions use it to see what you changed at each stop. Your staging area is never
  touched.
- At least one agent, installed and signed in:

  | Agent | What Pacenote runs | You need |
  |---|---|---|
  | Claude Code | `npx -y @agentclientprotocol/claude-agent-acp` | Node.js, and Claude Code signed in |
  | Codex | `npx -y @agentclientprotocol/codex-acp` | Node.js, and Codex signed in |
  | Gemini CLI | `gemini --experimental-acp` | The `gemini` CLI on your PATH, signed in |

## Getting started

1. Install **Pacenote AI** from **Settings → Plugins → Marketplace**.
2. Open the **Pacenote** tool window, on the right.
3. Pick an agent and chat, or choose **Start pairing session…** and describe what you want to build
   or change.

You can also right-click a selection and choose **Send Selection to Pacenote**, or find both actions
under **Tools → Pacenote**.

### Keyboard shortcuts at a stop

| Action | Windows / Linux | macOS |
|---|---|---|
| Accept the draft and go to the next stop | `Ctrl+Enter` | `⌘Enter` |
| Accept the draft | `Ctrl+Shift+Enter` | `⌘⇧Enter` |
| Leave the draft and go to the next stop | `Ctrl+Alt+Enter` | `⌘⌥Enter` |

## Your code and your data

Pacenote has no servers of its own and collects nothing.

- The agent you pick runs on your machine, as its own CLI. What you send it, and the code it reads,
  goes to that agent's provider under your account with them, as it would if you ran the agent
  yourself.
- To let the agent show you code and drafts, Pacenote runs a small MCP server for it on
  `127.0.0.1`, reachable only from your machine and only through a per-session secret URL.
- By default the agent may read, search and run commands such as your tests without asking. File
  edits, deletes, a `git push` and tools from other MCP servers are put to you first. A preset can
  loosen this for chat, but a pairing session always asks.

## Building from source

Requires JDK 21; Gradle comes with the wrapper.

```sh
./gradlew buildPlugin   # the plugin zip, in build/distributions
./gradlew test          # the tests
./gradlew runIde        # a sandboxed WebStorm with the plugin
./gradlew runRider      # a sandboxed Rider with the plugin
./gradlew verifyPlugin  # the JetBrains Plugin Verifier, against WebStorm and Rider
```

To run against IDEs you already have installed, set `localIdePath` (WebStorm) and `localRiderPath`
in `gradle.properties`, or in `~/.gradle/gradle.properties` for your machine alone.

For debugging:

- `PACENOTE_ACP_TRACE=<directory>` writes each agent session's protocol traffic to a file in that
  directory.
- `PACENOTE_LIVE_ACP=1` enables a live test against a real agent, which needs `npx` and network
  access.
