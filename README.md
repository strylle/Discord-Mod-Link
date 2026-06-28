# Discord Tag (Fabric, Minecraft 1.21.1)
A small server-side mod coded by me to solve a simple problem. 
Some people's usernames aren't the same as on Discord, so this allows you to link your username & also allow any server admins to know who is who in case any rules are broken.

When someone joins without a previous record, it nudges them to set their Discord username. (WIP: Cannot move or do anything until verified) 

Once set, that name shows as a suffix next to their name in the tab list *and* in the nametag above their head (using a vanilla scoreboard team) Anyone can turn it off for themselves at any time.

This is a **server-only** mod, only the server needs Fabric Loader + Fabric API + this mod's jar.

## Commands (anyone can run these on themselves)

- `/discordtag set <username>` - saves your Discord username and displays it.
- `/discordtag enable` - enables tag display
- `/discordtag disable` - hides your tag
- `/discordtag` - shows your status

On first join with no saved record, the player gets a one-time chat message with a clickable `[click to set it]` link that pre-fills `/discordtag set ` in their chat box and a `/discordtag disable` shortcut.