# ComputerCraft Turtle Swarm Manager
## Constraints:
### scan cost:
- each scan uses a certain amount of fuel based on the radius
- scan is cube shaped
### storage
- 16 storage slots in inventory:
- peripheralium hub can allow for more peripherals, but some might have to be in inventory
- fuel in inventory, blocks etc
- shulker boxes should work for bigger storage tho
### bandwidth
- due to http overhead have a single turtle connected via websocket
- other computers will communicate with that one via rednet to send data to the server
## Approach
- turtles scan the world as they travel and upload the scans to the server
- the server stores the entire world, compressing the blocks into a few types (LODs).<br> E.g. `protected` (turtles can enter but not break stuff), and then a few generic block types: `stones`, `dirts`, etc
- octrees and run length encoding on the 3d voxel map.
- scan zones will have a ttl invalidation, accelerated by player heatmap frequency, so that places where the player often is, expire faster prompting more frequent re-scans compared to places where the player never enters.
- turtles also generate their own heatmaps to optimize highways
- a* pathfinding in virtual server world with weights for each voxel: air:1, blocks, extra for the extra fuel + expensive blocks have higher cost to avoid breaking them if possible.
- scan always before interacting with the world. e.g. breaking or placing to have a guarantee that the world is cached correctly.
## Data model
Turtle Struct:

- Name
- id
- Location
- Storage
- Fuel
- Queue
- Task
- Parent
- Children
### Current Implementation:

## Open questions
## Rejected alternatives + why

## Viewer / Control GUI

A 3D inspector for the cached world, built as a separate client against the server API.

### Rendering
- 3D voxel view of the cached world plus live turtle positions
- **Tunnel visibility:** tunnels are their own block type, rendered as a
  distinct layer. Generic air renders on top with backface culling and
  flipped normals, so tunnel interiors stay visible through surrounding
  terrain. Turtles render inside/on top.
- Greedy meshing for chunk geometry, instanced rendering for turtles

### Inspection
- Filter and color blocks by attribute: type, TTL / staleness, scan age,
  importance weight, protected/breakable
- Overlay heatmaps: player presence, turtle traffic, rescan priority
- Display planned A* paths per turtle
- Highlight discrepancies between cached and last-scanned state

### Turtle management
- Live stats per turtle: fuel, inventory, current task, queue depth
- Parent/child and coalition relationships, visualized
- Manual control: remote reboot, halt, reassign task, manual movement
- Task queue inspection and reordering

### Stack

**Rust.**

**Rendering**
- `wgpu` — modern GPU abstraction over Vulkan / Metal / DX12 / WebGPU.
  Vulkan-level control without Vulkan-level boilerplate. Compute shaders
  available if needed later.
- `winit` — windowing and input events
- `egui` — immediate-mode UI, docked panels over the 3D viewport
- `glam` — vector/matrix maths (or `nalgebra` if more is needed later)
- `bytemuck` — safe casting of structs to GPU buffers

**Alternatives considered**
- `bevy` — full engine, ECS, built-in camera controllers and picking.
  Faster to prototype. Rejected for now: ECS is a second architecture to
  learn alongside Rust and graphics.
- `three-d`, `rend3` — higher-level renderers, less control
- **Raw Vulkan / OpenGL — rejected.** ~1000 lines before a triangle.
  Boilerplate cost with no benefit at this scale.

**Optimisation** (all standard, all understood from CGI work)
- Backface culling
- Frustum culling
- Greedy meshing — merge coplanar same-type faces into large quads
- Instanced rendering for turtles
- LOD via the existing octree levels
- Chunk-level occlusion culling if it ever proves necessary

Scan geometry is largely axis-aligned boxes (16×16×N corridors), so meshed
chunks are extremely light. Combined with octree compression and lossy
handling of low-importance regions (random caves, single ores), throughput
is unlikely to be the constraint.

**Networking**
- `reqwest` or `ureq` — HTTP client against the server API
- `tokio-tungstenite` — websocket for live turtle state
- `serde` + `serde_json` — deserialisation

Talks to the server over the same API the turtles use. Read-only by default,
with an explicit control mode for anything that moves a turtle.
