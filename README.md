# Discord Tag (Fabric, Minecraft 1.21.1)
A small server-side mod coded by me to solve a simple problem. 
Some people's usernames aren't the same as on Discord, so this allows you to link your username & also allow any server admins to know who is who in case any rules are broken.

The mod runs an embedded Discord bot within the JVM that will send a confirm/deny prompt to the claimed user.

This is a **server-only** mod, only the server needs Fabric Loader + Fabric API + this mod's jar.

## Verification is mandatory

By default, unverified players will be **frozen** and cannot move, break/place/use blocks, attack, or take damage until they verify. This is configurable to be a softer "nag" mode that just pesters them for whatever reason if needed.

### Discord Bot
A discord bot is needed in order for this mod to function! It's token and targeted server id will be necessary for the server to properly start.

## Commands (anyone can run these on themselves)

- `/discordtag set <username>` - saves your Discord username and displays it.
- `/discordtag enable` - enables tag display
- `/discordtag disable` - hides your tag
- `/discordtag` - shows your status
- `/discordtag verify` - re-sends the verification request if expired or ignored
- `/discordtag unlink` - unlinks your own verified account

## Operator commands 
- `/discordtag blacklist add|remove <discord-id-or-username>` - blocks (or unblocks) a Discord account from ever completing verification. If revoked when online, they'll be unable to move as if they are unverified. It does not ban them from the server, just permanent limbo
- `/discordtag blacklist list` - lists all blacklisted users

## Discord commands

- `/unlink` - lets a verified Discord user unlink their own Minecraft account.

## Discord administrator commands 

- `/blacklist add|remove user:@someone` / `/blacklist list` - same as the in-game blacklist commands, restricted to anyone with the **Ban Members** permission.
