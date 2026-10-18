# CCManager — A computercraft fleet system
> i need to give this a better name cuz this suck

# What is this?
In short: a programmable, remotely managed worker fleet to do basically anything. Vague, i know. It's a project in it's very early stage, which, currently my vision for this, is based on a few main problems with modern modded minecraft bases, so i think the "why" will help answer the what.
### Just a small list of key words of this adds
- Offline Work/management
- Autonomous Mining
- Base inventory Managent
- Item Logistics
- Building
- Resource Gathering
- Crafting
- Machine Management

# Curently: 
as of: `18.10.2026`  
The system has the following implemented in each module:
### Server (go)
- websocket connectiosn for client and broker
- Online copy of lua script for turtles & broker to auto-update
- World Storage
- Scan processing
- turtle state management
- pathfinding
### Turtles & Broker (lua)
- Auto Update from server
- Reboot / update on remote command
- websocket connection via broker, directed to each turtle
- scanning
- basic movement commands
- status updates
### Client (kotlin)
- Turtle bounding boxes and tracers
- nametags and info panels on hover
- commands can be executed from the client
- show scan visually on each scan
- show explored nodes during pathfind

# Why?
If you've ever played Minecraft with technical mods, you understand this problem. Without thorough planning, previous knowledge, or lots of time spent, your base very quickly becomes an incomprehensible mess of wires, shafts, pipes and so much other things.

Lots of these mods also have pretty poor interfaces between one another. and connecting up a more complicated system requires a huge amount of spacial planning, keeping the machine relatively cheap, and minimizing lag.

# What? (again)
knowing this, this is what CCManager aims to fix. among other things. Instead of having a huge amount of pipes connecting everything, working around unintuitive filter and organization uis, CCManager aims to provide a single solution for all this. instead of pipes carrying items, turtles will do that instead. As an extra bonus, though,  because of the nature of such a system, this creates a much "smarter", dynamic solution. where not only can you create complex rules, filters, etc, you can have the turtles actually build and expand your storage or machinery, control it. turn it on and off when needed, perform inventory management for your whole base, and more.

Now, that is only one of the many more features planned for this, and this project will contnue growing and developing as long as i have the desire to do so. The biggest problem is that it relies on a lot of exterior systems. mainly computercraft addons. Granted, there are solutions for all of it, and this is somethingf that this project is exactly here to solve. use acommodations provided by the modpack to the fullest, but also have workarounds.

Does your modpack not have a chunkloader system? Well, if any other chunkloader exists, that can potentially be used, having turtles manually place and enable chunkloaders. Worse case scenario, turtles may possibly have to build vanilla chunkloaders in chunks they are active in.

Does your modpack not have a block scanner for computercraf? That is actually one thing that has 2 and mabye a half nice solutions depending how far I wanna go. first of all, there is a mod client/util mod for this. this mod can feed the server with an initial scan of the world, getting a huge amount of data very fast, and then the turtles only inspect the world as they interact walk around.  
Second option is purely turtle inspection, which could use some averages, unknown and prediciton algorithms, to get a general idea of the world, and just get like a kinda blurry view of the world.  
or, A third option is to have a real copy of the world with the same seed on the server for the server to inspect and serve.

# How?
## First, the interface
The interface will be split into 2, mabye 3 points depending on future decisions and how it's decided to be run.
1. The current interface, is an ingame mod. mainly for debugging, providing quick info, and, just interacting withthe turtles while you play.
2. The initial idea, a native, local client, to view, and interact with the fleet remotely. This would give much more insight, and a full, scratch like, visual logic editor to implement advanced logic into your systems
3. A simple tui in the server.

## Worked Example:
Let's imagine a real scenario.
> You have just built a new machine that produces a certain item. this item is needed by a number of other machines, and you also always want a certain amount of that material in your storage.

You have 2 options to create this:  
In-game, or in the Client.  
**In-game:** you select the output chest of the machine, and the one or more input positions. then you can edit logic. meanwhile one of the turtles will already be approaching to investigate the chest. (or the client can simply feed the server with the content of the chest.)  
You can then define some brackts for if the amount of items in this chest or in the full system is something relative to a chosen number, do you wanna turn off the machine? or feed items to a certain machine, or put them in the storeage.  
**In the client:** It's similar, but you simply rely on info already gathered by the fleet. you click the chest, while a turtle, this time HAS to inspect it. you then do the same as in the client.
