# Station type icons

One icon per ETSI station type (ETSI TS 102 894-2), drawn by tsubotitsch. `manifest.json` lists the station type
value, its ASN.1 name, the German label and the file; `overview.svg` shows all of them.

Cyclist, passenger car, light truck, heavy truck and agricultural vehicle also have a variant that pulls a trailer,
`*-trailer.svg`, also drawn by tsubotitsch: the vehicle, scaled down, with a trailer on a drawbar behind it.
`manifest.json` lists it as `trailerFile` of the station type; `overview.svg` does not show the variants. No vehicle
profile of the app is agricultural, so `14-agricultural-trailer.svg` is a source only.

The app uses the icons of its vehicle profiles as vector drawables,
`android-app/app/src/main/res/drawable/ic_station_type_*.xml`, converted from these files without changes to the
artwork: the dark rounded square, the teal strokes and the filled shapes that cover lines behind them. The trailer
variants of cyclist, passenger car, light truck and heavy truck are `ic_station_type_*_trailer.xml`, with their nested
groups as nested `<group>` elements that translate and scale like the SVG transforms. Settings, Home and the CAM
Broadcast page show them when the profile has a trailer; the map keeps the plain icon and draws the trailer itself.
