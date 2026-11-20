---
tags: [contact-hub, feature-plan]
feature: Mock location
status: built on branch mock-location (PR open)
---

# Contact Hub: Mock location

Copy of **Mock Locations (fake GPS path)** by dvaoru, built into Contact Hub.
Lives in **Settings → Developer → Mock location**.

## Your 30 answers
| # | Question | Answer |
|---|---|---|
| 1 | App to copy | Mock Locations (dvaoru) |
| 2 | Where it lives | Settings → Developer |
| 3 | Modes | Single point, route, saved favorites, route that ends by staying at the last point |
| 4 | Map | Mapbox (free) |
| 5 | Picking a point | Long-press map, search address, paste coordinates, a contact's address |
| 6 | Real location on map | Yes, blue dot |
| 7 | Map style | Street/satellite toggle + follows dark mode |
| 8 | Route drawing | Snap to roads |
| 9 | Speed control | Presets + slider |
| 10 | Units | mph |
| 11 | Route end | Choose per route (stay / stop / loop / back and forth) |
| 12 | Natural speed | Slow down at turns and stops |
| 13 | Sitting still | Small random drift |
| 14 | Altitude | Auto from real ground elevation |
| 15 | Pause/resume | Yes |
| 16 | Start from real location | Yes, as an option |
| 17 | Controls while mocking | Notification only |
| 18 | Quick Settings tile | Built into Contact Hub |
| 19 | Favorites save | Points + routes (with speed and end behavior) |
| 20 | GPX/KML | Skip |
| 21 | History | Yes |
| 22 | Schedule a start time | Yes |
| 23 | Setup | Guided walkthrough |
| 24 | After reboot | Resume automatically |
| 25 | Update interval | Every 5 seconds |
| 26 | Contact Hub's Life360 map | Shows you at the mock location |
| 27 | In-app indicator | Small icon only |
| 28 | Look | Match Contact Hub (iOS-style motion) |
| 29 | Self-test | Yes, mock checks in the .txt report |
| 30 | Shipping | Branch + PR, and this plan in the vault |

## How it works
- Android only lets **one** app fake GPS: the one picked in **Developer options → Select mock location app**. The walkthrough opens each screen for you.
- A foreground service feeds the fake location to the GPS, network and fused providers every 5 seconds, so every app sees it.
- Routes are turned into a timeline: speeds up from a stop, slows for turns (sharper turn = slower), slows to a stop at the end.
- When sitting at a point (or paused, or after "stay at last point"), the location wobbles a few feet and accuracy moves between 3 and 8 m, like a real phone.
- Altitude comes from Open-Meteo's free elevation API (no key).

## Map, search and roads
| Thing | With a free Mapbox token | Without a token |
|---|---|---|
| Street map | Mapbox Streets | OpenStreetMap |
| Dark mode | Mapbox Dark | OpenStreetMap, colors inverted |
| Satellite | Mapbox Satellite Streets | Not available |
| Search | Mapbox Geocoding | OpenStreetMap Nominatim |
| Snap to roads | Mapbox Directions | OSRM public server |

Mapbox's free tier covers far more than one person uses. You need a free account at mapbox.com → copy the **default public token** (starts with `pk.`) → paste it in Mock location → ⚙️.

## Known limits
- **Real blue dot while mocking:** once mocking is on, Android hands every app the fake location, Contact Hub included. So while mocking, the blue dot is your last real fix from before you started, labeled with how old it is.
- **Scheduled starts** need "Alarms & reminders" allowed for Contact Hub, or Android may delay them.
- Any app can ask Android whether a location is mocked. Contact Hub doesn't try to hide that.
- No floating bubble (you picked notification controls only). No GPX/KML.

## Files
- `data/MockLoc.kt`: route math, speed profile, drift, coordinate parsing (unit tested)
- `engine/MockService.kt`: foreground service, notification, Quick Settings tile, boot resume, schedules
- `engine/MockApi.kt`: map tiles, search, snap to roads, elevation
- `ui/MockScreen.kt`: map screen, walkthrough, favorites, history, schedules
- `engine/Diagnostics.kt`: "Mock location" checks in the self-test
