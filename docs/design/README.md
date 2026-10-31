# App icon

`app-icon.svg` is the editable vector mark. Android uses the matching paths in
`app/src/main/res/drawable/app_icon.xml`, shared by normal, round, and themed
launcher icons. The 108dp adaptive foreground keeps the mark inside the safe
area over a white background; Android supplies the outer mask.

`app-icon-reference.png` was prepared from the user's mockup using the built-in
image generation tool. The final shipping artwork was redrawn as vector paths
for clean edges at all densities.

Image generation prompt:

> Use case: background-extraction. Edit the supplied image into a production Android launcher foreground asset. Preserve the exact visual identity: three overlapping rounded rectangular blue card outlines, front card upright, two rear cards fanning toward lower left, same bright blue, same proportions, same stroke weight. Remove only the white background and unnecessary outer whitespace. All interiors and spaces between the blue outlines must be genuinely transparent. Center the complete mark on a square transparent canvas, occupying 90% of width and height with equal optical margins; do not clip any part. Clean crisp flat blue edges, no paper texture, no gradients, no shadows, no rounded square tile, no text. This will be scaled into the Android adaptive-icon safe zone over a white background.
