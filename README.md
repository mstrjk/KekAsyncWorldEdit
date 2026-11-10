# KekAsyncWorldEdit
KekAsyncWorldEdit is a reimplementation of EngineHub's WorldEdit and IntellectualSite's FastAsyncWorldEdit for Folia and Paper 26.x+.

## Features
Everything from FastAsyncWorldEdit, plus:
- `-e` support on all alteration commands for entity removal and recovery
- //bound to create a selection area around you without //pos or wands
- `//setwand` alias
- History that persists across server restarts
- Brigadier command registration for all commands
- Optional PDC-based tool registration
- `/cancel` works mid-edit

## Installation
1. Download the latest release of KekAsyncWorldEdit from the GitHub repository.
2. Remove WorldEdit and FastAsyncWorldEdit jars and directories from your server's `plugins` directory.
3. Place the downloaded JAR file in your server's `plugins` directory.
4. Start your server.

## Self-build
Refer to our `build.ps1` script in the repository root for local builds.
*\* To build locally, your device will download the required dependencies at execution time.*

## Warning
KekAsyncWorldEdit cannot run alongside WorldEdit or FastAsyncWorldEdit, even on Paper. Ensure that both are removed before installing KekAsyncWorldEdit.

## Attributions
KekAsyncWorldEdit did not go through a clean-room reimplementation. For the benefit of doubt and recognition of the original projects, KekAsyncWorldEdit is licensed under the same GPL v3 license as WorldEdit and FastAsyncWorldEdit.

## Benchmarks
### FastAsyncWorldEdit does not support Folia, its testing was done exclusively on Paper.
## 10,000,000 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | ❗ **192 ms** | 234 ms | 620 ms | 285 ms |
| `//set 50%stone,50%dirt` | ❗ **791 ms** | 850 ms | 1.25 s | 854 ms |
| `//replace stone glass` | ❗ **176 ms** | 286 ms | 1.04 s | 288 ms |
| `//count dirt` | ❗ **32 ms** | 93 ms | 217 ms | 204 ms |
| `//copy` | ❗ **42 ms** | 88 ms | 273 ms | 170 ms |
| `//paste` | ❗ **846 ms** | 893 ms | 1.43 s | 1.11 s |
| `//undo` | ❗ **349 ms** | 411 ms | 1.00 s | 620 ms |
| `//rotate 90 + //paste` | ❗ **905 ms** | 1.05 s | 1.46 s | 1.26 s |
| `//sphere glass` | ❗ **303 ms** | 415 ms | 1.81 s | 1.09 s |
| `//smooth 1` | ❗ **120 ms** | 222 ms | 692 ms | 369 ms |
| `//regen` | ❗ **1.03 s** | 1.15 s | 2.00 s | 1.54 s |

## 1,000,000 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | ❗ **24 ms** | 25 ms | 225 ms | 137 ms |
| `//set 50%stone,50%dirt` | 79 ms | ❗ **75 ms** | 269 ms | 212 ms |
| `//replace stone glass` | ❗ **26 ms** | 27 ms | 278 ms | 161 ms |
| `//count dirt` | 5 ms | ❗ **4 ms** | 113 ms | 267 ms |
| `//copy` | 5 ms | ❗ **5 ms** | 33 ms | 20 ms |
| `//paste` | ❗ **86 ms** | 88 ms | 271 ms | 215 ms |
| `//undo` | ❗ **38 ms** | 74 ms | 277 ms | 154 ms |
| `//rotate 90 + //paste` | ❗ **85 ms** | 88 ms | 250 ms | 191 ms |
| `//sphere glass` | ❗ **43 ms** | 46 ms | 325 ms | 200 ms |
| `//smooth 1` | ❗ **24 ms** | 27 ms | 203 ms | 139 ms |
| `//regen` | 313 ms | ❗ **277 ms** | 2.19 s | 2.10 s |

## 100,000 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | 11 ms | ❗ **9 ms** | 176 ms | 123 ms |
| `//set 50%stone,50%dirt` | 15 ms | ❗ **13 ms** | 183 ms | 124 ms |
| `//replace stone glass` | 11 ms | ❗ **9 ms** | 161 ms | 119 ms |
| `//count dirt` | 3 ms | ❗ **2 ms** | 79 ms | 79 ms |
| `//copy` | 3 ms | ❗ **2 ms** | 19 ms | 4 ms |
| `//paste` | 17 ms | ❗ **14 ms** | 181 ms | 125 ms |
| `//undo` | 12 ms | ❗ **10 ms** | 175 ms | 136 ms |
| `//rotate 90 + //paste` | 18 ms | ❗ **15 ms** | 189 ms | 125 ms |
| `//sphere glass` | 13 ms | ❗ **11 ms** | 172 ms | 135 ms |
| `//smooth 1` | 6 ms | ❗ **5 ms** | 183 ms | 122 ms |
| `//regen` | 189 ms | ❗ **155 ms** | 685 ms | 747 ms |

## 1,000 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | 6 ms | ❗ **4 ms** | 183 ms | 113 ms |
| `//set 50%stone,50%dirt` | ❗ **5 ms** | 5 ms | 226 ms | 128 ms |
| `//replace stone glass` | 5 ms | ❗ **4 ms** | 176 ms | 119 ms |
| `//count dirt` | 2 ms | ❗ **1 ms** | 31 ms | 31 ms |
| `//copy` | 2 ms | ❗ **2 ms** | 11 ms | 2 ms |
| `//paste` | 5 ms | ❗ **5 ms** | 182 ms | 114 ms |
| `//undo` | 5 ms | ❗ **4 ms** | 194 ms | 114 ms |
| `//rotate 90 + //paste` | ❗ **4 ms** | 51 ms | 152 ms | 112 ms |
| `//sphere glass` | 6 ms | ❗ **3 ms** | 185 ms | 114 ms |
| `//smooth 1` | ❗ **2 ms** | 51 ms | 209 ms | 174 ms |
| `//regen` | ❗ **77 ms** | 108 ms | 540 ms | 412 ms |

## 100 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | 3 ms | ❗ **2 ms** | 162 ms | 49 ms |
| `//set 50%stone,50%dirt` | 3 ms | ❗ **2 ms** | 162 ms | 48 ms |
| `//replace stone glass` | 2 ms | ❗ **2 ms** | 162 ms | 49 ms |
| `//count dirt` | 2 ms | ❗ **1 ms** | 16 ms | 16 ms |
| `//copy` | 3 ms | ❗ **2 ms** | 12 ms | 2 ms |
| `//paste` | 3 ms | ❗ **2 ms** | 161 ms | 49 ms |
| `//undo` | 3 ms | ❗ **2 ms** | 161 ms | 48 ms |
| `//rotate 90 + //paste` | 3 ms | ❗ **1 ms** | 154 ms | 50 ms |
| `//sphere glass` | 3 ms | ❗ **2 ms** | 149 ms | 112 ms |
| `//smooth 1` | 2 ms | ❗ **1 ms** | 2 ms | 1 ms |
| `//regen` | ❗ **48 ms** | 50 ms | 460 ms | 452 ms |

## 10 blocks changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | 2 ms | ❗ **1 ms** | 162 ms | 49 ms |
| `//set 50%stone,50%dirt` | 1 ms | ❗ **1 ms** | 156 ms | 48 ms |
| `//replace stone glass` | 2 ms | ❗ **1 ms** | 156 ms | 48 ms |
| `//count dirt` | 1 ms | ❗ **1 ms** | 14 ms | 17 ms |
| `//copy` | 2 ms | ❗ **1 ms** | 13 ms | 2 ms |
| `//paste` | 2 ms | ❗ **1 ms** | 162 ms | 53 ms |
| `//undo` | 2 ms | ❗ **1 ms** | 161 ms | 48 ms |
| `//rotate 90 + //paste` | 2 ms | ❗ **1 ms** | 161 ms | 48 ms |
| `//sphere glass` | 2 ms | ❗ **1 ms** | 156 ms | 110 ms |
| `//smooth 1` | 2 ms | ❗ **1 ms** | 2 ms | 1 ms |
| `//regen` | ❗ **34 ms** | 52 ms | 455 ms | 452 ms |

## 1 block changed

| Command | KAWE (Paper) | KAWE (Folia) | FAWE (default) | FAWE (fast config) |
|---|---|---|---|---|
| `//set stone` | 2 ms | ❗ **1 ms** | 156 ms | 50 ms |
| `//set 50%stone,50%dirt` | 2 ms | ❗ **1 ms** | 156 ms | 49 ms |
| `//replace stone glass` | 2 ms | ❗ **1 ms** | 157 ms | 50 ms |
| `//count dirt` | 1 ms | ❗ **1 ms** | 12 ms | 16 ms |
| `//copy` | 2 ms | ❗ **2 ms** | 6 ms | 2 ms |
| `//paste` | 2 ms | ❗ **1 ms** | 157 ms | 48 ms |
| `//undo` | 2 ms | ❗ **1 ms** | 157 ms | 50 ms |
| `//rotate 90 + //paste` | 1 ms | ❗ **1 ms** | 157 ms | 51 ms |
| `//sphere glass` | 2 ms | ❗ **1 ms** | 156 ms | 110 ms |
| `//smooth 1` | 1 ms | ❗ **1 ms** | 1 ms | 1 ms |
| `//regen` | ❗ **48 ms** | 94 ms | 455 ms | 451 ms |

## Stats for nerds
- Each configuration ran 11 commands at 7 sizes (10M down to 1 changed blocks) on Minecraft 26.3 with one connected client. 
- Every cell had 5 measured repeats after a discarded JVM warm-up pass, in a fixed-seed shuffled order, and each repeat started from the same clean world snapshot: untimed undo plus //clearhistory, view distance 16, simulation distance 10, 6 GB heap, and random ticks, mob spawning, mob griefing and fire spread disabled. 
- The timer runs from command dispatch until the later of two moments: the result chat packet being written to the client socket, and the last block-change packet inside the edit's bounding box being written. 
- Each cell reports the median of the five repeats.

### Benchmark harness
Our benchmark harness is not made available to the public.
