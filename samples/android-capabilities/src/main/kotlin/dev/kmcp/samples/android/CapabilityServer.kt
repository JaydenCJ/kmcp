package dev.kmcp.samples.android

import android.Manifest
import dev.kmcp.server.McpServer
import dev.kmcp.server.ToolArguments
import dev.kmcp.server.mcpServer
import dev.kmcp.server.toolError
import dev.kmcp.server.toolText
import dev.kmcp.types.CallToolResult

/**
 * Builds an [McpServer] that exposes device capabilities as MCP tools.
 *
 * Every tool is gated by one Android runtime permission: when the user has
 * not granted it, the tool answers with an in-band error result instead of
 * touching the data. This keeps the agent-facing contract explicit — the
 * agent can tell the user exactly which permission to grant.
 */
object CapabilityServer {
    fun build(
        gate: PermissionGate,
        contacts: ContactsProvider,
        calendar: CalendarProvider,
        notifier: Notifier,
    ): McpServer = mcpServer(
        name = "android-capabilities",
        version = "0.1.0",
        title = "Android system capabilities as MCP tools",
    ) {
        instructions(
            "Tools are permission-gated. A result with isError=true names " +
                "the Android permission the user still needs to grant.",
        )

        tool("contacts_search", description = "Search device contacts by display name") {
            input {
                string("query", description = "Substring of the contact display name", minLength = 1)
                integer("limit", description = "Maximum results", required = false, minimum = 1, maximum = 50)
            }
            handle { args ->
                withPermission(gate, Manifest.permission.READ_CONTACTS) {
                    val hits = contacts.search(args.string("query"), limitOf(args))
                    if (hits.isEmpty()) {
                        toolText("No contacts matched.")
                    } else {
                        toolText(hits.joinToString("\n") { c -> c.name + (c.phone?.let { p -> " <$p>" } ?: "") })
                    }
                }
            }
        }

        tool("calendar_upcoming", description = "List calendar events in the next N days") {
            input {
                integer("days", description = "Look-ahead window in days", minimum = 1, maximum = 31)
            }
            handle { args ->
                withPermission(gate, Manifest.permission.READ_CALENDAR) {
                    val events = calendar.upcomingEvents(args.long("days").toInt())
                    if (events.isEmpty()) {
                        toolText("No events in the requested window.")
                    } else {
                        toolText(events.joinToString("\n") { e -> "${e.startEpochMillis}\t${e.title}" })
                    }
                }
            }
        }

        tool("notification_post", description = "Post a local notification on the device") {
            input {
                string("title", minLength = 1, maxLength = 80)
                string("body", minLength = 1, maxLength = 400)
            }
            handle { args ->
                withPermission(gate, Manifest.permission.POST_NOTIFICATIONS) {
                    notifier.post(args.string("title"), args.string("body"))
                    toolText("Notification posted.")
                }
            }
        }
    }

    private fun limitOf(args: ToolArguments): Int =
        (args.longOrNull("limit") ?: 10L).toInt()

    private inline fun withPermission(
        gate: PermissionGate,
        permission: String,
        block: () -> CallToolResult,
    ): CallToolResult =
        if (gate.isGranted(permission)) {
            block()
        } else {
            toolError("Permission $permission has not been granted; open the app and grant it first.")
        }
}
