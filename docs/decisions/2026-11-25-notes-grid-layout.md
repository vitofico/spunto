# Notes grid layout on wide web screens

The owner asked for a way to scan many short notes at once on a wide screen.
This revises the "no density setting" choice in historical design document 03
§13.2 for the web home only. Documents 01 to 05 remain unchanged.

The home header gains one **Grid layout** toggle beside search and settings,
shown only from 1024 px wide, where the rail is visible. In grid layout the home
widens, day headers keep their full width, and each day's notes flow left to
right as opaque tiles of at least 18 rem, in reading order. Tiles use the same
raised paper, type, labels and previews as the column, so every colour theme,
installed pack, light/dark mode and background style applies unchanged. The
capture field keeps the column measure.

The column stays the default. The choice is stored locally per device, like the
background style, and is not sent to the server. Below 1024 px the toggle is
hidden and the column is always used, so phones are unaffected. Label, archive,
trash and plugin views, the note reader and Android keep the column.
