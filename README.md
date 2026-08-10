# Computercraft Turtle Swarm Manager
## Constraints:
### scan cost:
- each scan uses a certain amount of fuel based on the radius
- scan is cube shaped
### storage
- 16 storage slots in inventory:
- periphiraleum hub can allow for more peripherals, but some might have to be in inventory
- fuel in inventory, blocks etc
- shulker boxes should work for bigger storage tho
### bandwidth
- due to http overhead have a single turtle connected via websocket
- other computers will communicate with that one via rednet to send data to the server
## Approach
- turtles scan the world as they travel and upload the scans to the server
- the server stores the entire world, compressing the blocks into a few types (LODs).<br> E.g. `protected` (turtles can enter but not break stuff), and then a few generic block types: `stones`, `dirts`, etc
- octrees and run length encoding on the 3d voxel map.
- scan zones will have a ttl invalidation, accelerated by player heatmap frequency, so that places where the player often is expire faster prompting more frequent re-scans compared to places where the player never enters.
- turtles also generate their own heatmaps to optimize highways
- a* pathfinding in virtual server world with weights for each voxel: air:1, blocks, extra for the extra fuel + expensive blocks have higher cost to avoid breaking them if possible.
- scan always before interacting with the world. e.g. breaking or placing to have a guarante that the world is cached correctly.
## Data model
Turtle Struct:
```go
type Turtle struct {
    name string
    id int
    Transform
    storage storage
    fuel int
    queue []queue
    task task
    parent *Turtle
    children []*Turtle
    
}

type Transform struct {
    x = int
    y = int
    z = int
    direction = direction
}

direction enum:
north, west, east, south. 
```
## Open questions
## Rejected alternatives + why