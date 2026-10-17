# Fenza-PvP-Whitelisting

A dual Minecraft Paper server plugin and companion Fabric client mod system for silently verifying installed mods of targeted players, enforcing client honesty, and reporting results directly to Discord via webhooks.

---

## 📦 Project Architecture

| Module | Target Environment | Supported Minecraft Versions | Java Version |
| :--- | :--- | :--- | :--- |
| **`server-plugin`** | Paper / Purpur / Leaf Server | 1.21.x – 26.2+ | Java 21+ / Java 25 |
| **`client-mod`** | Modern Fabric Client | 26.1, 26.1.2, 26.2+ | Java 25 |
| **`client-mod-1.21`** | Legacy Fabric Client | 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4 | Java 21 |

---

## 🚀 Key Features

* **Silent Verification:** Operates invisibly on the client over custom plugin channel `fenza:sync` (no GUI, no chat messages, no notifications).
* **Targeted Mod Checking (`/modcheck`):**
  * When `check-specific-players-only: true` is enabled, only players added via `/modcheck <player>` are required to have the companion mod.
  * Normal players can join freely without needing the mod and are **never** checked or kicked.
  * If a tracked player joins without the mod, they are kicked after the timeout (default: 10s).
* **Discord Webhook Alerts:**
  * Real-time embed reports sent to your configured Discord webhook on player join.
  * Displays player name, UUID, verification status, gameplay mods list, and bundled library counts.
  * Sends an alert embed if a tracked player is kicked for missing the required mod.
* **Intelligent Library Filtering:**
  * Automatically filters internal language runtimes and bundled libraries (Kotlin runtime, Quilt parsers, MVEL, internal Fabric submodules) while keeping cheat/hack mods and actual mods front and center.
* **Persistent Disk Logging:** Saves all verified mod lists to `plugins/Fenza-PvP-Whitelisting/player-mods/<player>.txt`.

---

## 🛠️ Commands & Permissions

* `/modcheck <player>`: Add a player to the mod check watch list. (If online, triggers verification immediately).
* `/modcheck remove <player>`: Remove a player from the watch list.
* `/modcheck list`: View all currently tracked players.
* `/fenzamods <player> [--all]`: View the detailed mod list for an online player or offline saved file.

**Permissions:**
* `fenza.modcheck` (Default: OP)
* `fenza.modlist` (Default: OP)

---

## ⚙️ Configuration (`config.yml`)

```yaml
# When true, ONLY players on the tracked-players list will be required to have the mod on join.
check-specific-players-only: true

# List of players to check on join (managed via /modcheck <player>)
tracked-players: []

# Handshake timeout before kicking missing-mod players
handshake-timeout: 10

# Discord Webhook configuration
discord-webhook:
  enabled: true
  url: "" # Insert your Discord Webhook URL here
  alert-on-missing-mod: true

# Banned mod IDs that cause an immediate kick
banned-mods: []
#  - "wurst"
#  - "meteor-client"
#  - "aristois"
```

---

## 🔨 Building from Source

### Modern Client Mod (26.1+):
```bash
cd client-mod
./gradlew build
```

### Legacy Client Mod (1.21.x):
```bash
cd client-mod-1.21
./gradlew build
```

### Server Plugin:
```bash
cd server-plugin
./gradlew build
```
