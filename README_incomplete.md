# Gamut - A ComputerCraft swarm system
## Description:
this is a system made to autonomously manage a base within a modded Minecraft server.

A number of non-ComputerCraft mods are on the server, but this project tries to be as independent of them as possible

As of now, the project relies on a number of mods too. CCChunkloader, Plethora and more.
<br>Specifically their: ChunkLoaders, block scanners, and likely more to come

The main system relies on turtles navigating the world, scanning it as they go, and sending all data to the server.
All communication is done via a static broker computer which holds a single websocket with the server open,
acting as the hub for the hub and spoke architecture of this. connecting the server to each of the turtles.

The main use of the turtles, is to define a set of io blocks. e.g. farms, storages, processing machines, etc;
and the turtles serve as a dynamic smart pipe. instead of constantly routing and rerouting modded pipes and
conveyors withing the game, the turtles will transfer items, allowing for advanced lookups and filters to be used
they will also have a number of other abilities such as autonomous mining, but for the time being pipes are the main priority. 

