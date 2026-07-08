package dev.gesp.structural.minecraft.recording;

import dev.gesp.structural.recording.RecordingSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Handles recording subcommands for /strux record.
 *
 * <pre>
 *   /strux record stop          - Stop current recording
 *   /strux record list          - List saved recordings
 *   /strux record replay <name> - Replay with particle visualization
 *   /strux record export <name> - Export JSON to player's data folder
 *   /strux record clear         - Delete all recordings
 * </pre>
 */
public class RecordingCommand {

    /** Tag for recordings started by hand via {@code /strux record start}. */
    private static final String MANUAL_TAG = "manual";

    private final Plugin plugin;
    private final MinecraftEventRecorder recorder;
    private final RecordingService recordingService;

    public RecordingCommand(Plugin plugin, MinecraftEventRecorder recorder, RecordingService recordingService) {
        this.plugin = plugin;
        this.recorder = recorder;
        this.recordingService = recordingService;
    }

    /**
     * Handle a recording subcommand.
     *
     * @param sender who runs the command — a player in game, or the console/RCON
     *               (verifying recordings is operator tooling, not gameplay)
     * @param args   the arguments after "record" (e.g., ["stop"] or ["replay", "session-..."])
     * @return true if the command was handled
     */
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        // Gate destructive / expensive subcommands behind admin (only list and status are
        // read-only). Without this any player could stop a programmatic capture (e.g. a
        // Siege match recording), hog the single global recorder slot with start, or spam
        // CPU-heavy verify replays on async threads. clear() keeps its own check too.
        boolean readOnly = sub.equals("list") || sub.equals("status");
        if (!readOnly && !sender.hasPermission("structuralintegrity.admin")) {
            sender.sendMessage("§cYou need admin permission to manage recordings.");
            return true;
        }
        return switch (sub) {
            case "stop" -> stop(sender);
            case "start" -> start(sender, args);
            case "list" -> list(sender);
            case "replay" -> replay(sender, args);
            case "verify" -> verify(sender, args);
            case "export" -> export(sender, args);
            case "clear" -> clear(sender);
            case "status" -> status(sender);
            default -> {
                showHelp(sender);
                yield true;
            }
        };
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6Recording commands:");
        sender.sendMessage("§e/strux record status §7- Show current recording status");
        sender.sendMessage("§e/strux record stop §7- Stop current recording");
        sender.sendMessage("§e/strux record start §7- Start a new recording");
        sender.sendMessage("§e/strux record list §7- List saved recordings");
        sender.sendMessage("§e/strux record replay <name> §7- Replay a recording");
        sender.sendMessage("§e/strux record verify <name> §7- Verify determinism of a recording");
        sender.sendMessage("§e/strux record export <name> §7- Export recording to JSON file");
        sender.sendMessage("§e/strux record clear §7- Delete all recordings");
    }

    private boolean status(CommandSender sender) {
        if (recordingService.isRecording()) {
            sender.sendMessage("§aRecording active: §f" + recordingService.currentSessionId());
        } else {
            sender.sendMessage("§7Recording inactive");
        }
        sender.sendMessage("§7Sessions stored: §f" + recorder.listSessions().size());
        sender.sendMessage("§7Recordings dir: §f" + recorder.getRecordingsDir());
        return true;
    }

    private boolean stop(CommandSender sender) {
        if (!recorder.isRecording()) {
            sender.sendMessage("§cNo recording in progress.");
            return true;
        }
        // Single global session: the recorder stops whatever is active, whether it
        // was started by this command or programmatically via RecordingService.
        String sessionId = recorder.stopRecording();
        sender.sendMessage("§aRecording stopped: §f" + sessionId);
        return true;
    }

    private boolean start(CommandSender sender, String[] args) {
        if (recordingService.isRecording()) {
            sender.sendMessage("§cRecording already in progress (§f" + recordingService.currentSessionId()
                    + "§c). Stop it first with §e/strux record stop");
            return true;
        }
        // --json: write this session as JSON instead of the default binary .strx
        // (a debug/diff aid for inspecting a recording by eye).
        boolean json = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--json")) {
                json = true;
            }
        }
        // Route the command through the same service the programmatic API uses, so
        // there is one start path. Manual captures are tagged "manual" and labelled
        // with the player's name → recordings/manual/manual-<player>-<timestamp>.strx
        // From the console there is no body in a world: record the primary world
        // and label the capture "console".
        World recordWorld = sender instanceof Player player
                ? player.getWorld()
                : Bukkit.getWorlds().get(0);
        String label = sender instanceof Player player ? player.getName() : "console";
        RecordingRequest request =
                RecordingRequest.of(MANUAL_TAG, recordWorld).label(label).build();
        try {
            RecordingHandle handle = recordingService.startRecording(request);
            if (json) {
                recorder.setWriteJson(true);
            }
            sender.sendMessage("§aRecording started: §f" + handle.sessionId() + (json ? " §7(JSON)" : ""));
        } catch (IllegalStateException e) {
            sender.sendMessage("§c" + e.getMessage());
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        List<String> sessions = recorder.listSessions();
        if (sessions.isEmpty()) {
            sender.sendMessage("§7No recordings found.");
            return true;
        }
        sender.sendMessage("§6Recordings (§f" + sessions.size() + "§6):");
        for (String session : sessions) {
            sender.sendMessage("§7 - §f" + session);
        }
        return true;
    }

    private boolean replay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §e/strux record replay <session-name>");
            return true;
        }
        String sessionId = args[1];
        try {
            RecordingSession session = recorder.loadSession(sessionId);
            if (session == null) {
                sender.sendMessage("§cRecording not found: §f" + sessionId);
                return true;
            }
            sender.sendMessage("§aLoaded recording: §f" + sessionId);
            sender.sendMessage("§7Events: §f" + session.eventCount());
            sender.sendMessage("§7Duration: §f" + (session.getEndTimeMs() - session.getStartTimeMs()) + " ms");
            sender.sendMessage("§7World: §f" + session.getWorldId());
            // TODO: Implement actual replay with particle visualization
            sender.sendMessage("§e(Replay visualization not yet implemented)");
        } catch (IOException e) {
            sender.sendMessage("§cFailed to load recording: §f" + e.getMessage());
        }
        return true;
    }

    private boolean verify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §e/strux record verify <session-name>");
            return true;
        }
        String sessionId = args[1];
        sender.sendMessage("§7Verifying determinism for: §f" + sessionId);
        sender.sendMessage("§7(Check server console for results)");

        // Run async to not block main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String result = recorder.verifySession(sessionId);
            // Send result back on main thread. ALWAYS log it to the server console
            // too: an RCON client has disconnected long before this async result
            // arrives, so messages to that sender evaporate — the log is the one
            // place the report reliably lands (and what the command promises).
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (String line : result.split("\n")) {
                    plugin.getLogger().info("[verify " + sessionId + "] " + line);
                    if (line.contains("divergence") || line.contains("DIVERGENCE")) {
                        sender.sendMessage("§c" + line);
                    } else if (line.contains("deterministic")) {
                        sender.sendMessage("§a" + line);
                    } else {
                        sender.sendMessage("§7" + line);
                    }
                }
            });
        });
        return true;
    }

    private boolean export(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §e/strux record export <session-name>");
            return true;
        }
        String sessionId = args[1];
        Path sourcePath = recorder.getRecordingsDir().resolve(sessionId + ".json");
        if (!Files.exists(sourcePath)) {
            sender.sendMessage("§cRecording not found: §f" + sessionId);
            return true;
        }
        sender.sendMessage("§aRecording file location:");
        sender.sendMessage("§f" + sourcePath.toAbsolutePath());
        return true;
    }

    private boolean clear(CommandSender sender) {
        if (!sender.hasPermission("structuralintegrity.admin")) {
            sender.sendMessage("§cYou need admin permission to clear all recordings.");
            return true;
        }
        int deleted = recorder.clearAllSessions();
        sender.sendMessage("§aDeleted §f" + deleted + "§a recording(s).");
        return true;
    }
}
