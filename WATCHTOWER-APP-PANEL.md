# Watchtower panel for the Kai Ross Nexus app

This is what the app needs to show and send. Watchtower (the plugin) already does its half.

## What the plugin sends the app (no app changes needed)

1. `POST /functions/pluginAlert` for every yellow and red alert, plus "Server started", "Server stopping", a daily digest (quiet), and "Watchtower held back N message(s)". `source` is `NexusWatchtower`, `url` is `/command`. Categories used: `security` (operator changes, file tampering, guard, lockdown, probes), `world` (mass breaking, TNT, lava, fire, command blocks), `player` (watched joins, chat phrases), `plugin`, `server`, `system`.
2. `POST /functions/ingestGameEvent` every 30 seconds while a watched player is active: `event_type: "operator_activity"`, `source: "NexusWatchtower"`, `fire_kai: false`, `details.lines` holds up to 40 text lines. No `player_name`, so the director cannot act on it. If the app turns out to reject unknown event types, tell me and I will switch it to `anomaly`.

3. **New in 0.3.0:** `POST /functions/ingestGameEvent` with `source: "plugin"`, `server_id`, and up to 50 `events` per request, every 2 seconds while anything happens. Each event has `event_type` (`chat`, `player_join`, `player_quit`, `player_death`, `mob_kill`, `player_kill`, `block_break`, `block_place`, `command`, `advancement`, and optionally `damage`, `item_pickup`), `player_name`, and where useful `description` (max 400 characters), `target_name` and `location` ("world x y z"). Never `fire_kai`. Every player is included, owners and operators too. Password commands and private-message text are replaced by `[hidden]`, IP addresses by `[ip]`. This is what fills the "SERVER TO PHONE" panel.

## What the app sends the plugin

Through the same command path it already uses (http-commands `/execute`, or the Bloom Host panel). One command per button:

| Button | commands[] entry |
| --- | --- |
| Refresh | `watchtower snapshot` |
| Freeze player | `watchtower lock NAME` |
| Release player | `watchtower unlock NAME` |
| Watch (guard) | `watchtower add NAME guard` |
| Watch (observe) | `watchtower add NAME observe` |
| Stop watching | `watchtower remove NAME` |
| LOCKDOWN everyone | `watchtower lockdown on` |
| Lift lockdown | `watchtower lockdown off` |
| Recent activity | `watchtower log NAME 30` |
| Approve plugin files | `watchtower baseline` |
| Send test alert | `watchtower test` |
| Feed health / test line | `watchtower feed` / `watchtower feed test` |
| Shut server down | `stop` |

`NAME` must be validated by the app before it is put in a command: `^\.?[A-Za-z0-9_]{3,16}$` (the optional leading dot is how Floodgate shows Xbox players). Never put free text from a player into a command.

Replies come back in `outputs[]` as plain text (no colour codes). Only `snapshot` is machine-readable.

## The snapshot JSON (one line)

```json
{
  "server": "Nexus Universe",
  "time": 1762131000000,
  "armed": true,
  "lockdown": false,
  "watched": [
    { "name": "Shady", "mode": "guard", "frozen": false, "online": true }
  ],
  "operators": ["Pal", "RealSociety5107"],
  "unexpectedOperators": ["Shady"],
  "heldBack": 0,
  "files": "all 31 files match the approved baseline",
  "app": "on (12 sent, 0 failed, 0 waiting, last answer 200)"
}
```

`mode` is `guard` or `observe`. `unexpectedOperators` lists operators who are neither owners nor on the `operators.expected` list: show it in red when it is not empty.

## Prompt to give the app's builder

> Add a "Watchtower" tab to the admin-only Command Center (/command). Keep it admin-only.
>
> Every 15 seconds while the tab is open, and when I press Refresh, send the console command `watchtower snapshot` through the existing server-command path and parse the first output line as JSON (ignore the output if it does not parse and show "server unreachable").
>
> Show: a big status strip (armed / not armed, server name, a red LOCKDOWN banner when `lockdown` is true, a red warning listing `unexpectedOperators` when not empty, and the `files` text, in red if it does not start with "all"). Under it a table of `watched` players with name, mode, a "frozen" badge, an "online" dot, and buttons: Freeze or Release, Switch mode, Stop watching, and Recent activity (shows the output of `watchtower log NAME 30` in a drawer). An "Add player to watch" form with a name field and a guard/observe choice. Above the table, three big buttons: LOCKDOWN everyone (`watchtower lockdown on`), Lift lockdown (`watchtower lockdown off`), and Shut server down (`stop`). LOCKDOWN and Shut server down must ask "Are you sure?" first and Shut server down must also require typing the word STOP.
>
> Validate every player name against `^\.?[A-Za-z0-9_]{3,16}$` before it goes into a command, and never send anything else typed by a user. Show the text output of every command in a small log under the table.
>
> Also show a "Watchtower activity" list on the same tab: the latest 50 GameEvents with `event_type` = `operator_activity` (newest first, show `details.lines`), and the latest 20 CommandAlerts whose `source` = `NexusWatchtower`. Do not send these events to the Kairos director.
