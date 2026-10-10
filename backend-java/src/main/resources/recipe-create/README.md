# Recipe creation compatibility data

`defaults.json` contains exactly the two recipe default strings from the existing `mealie/lang/messages/*.json` files at baseline 78a38cbb12c60124ad143a6ede562002b0ed5d11. Locale files are unmodified. Python selects an exact locale key, with en-US fallback.

`html-entities.json` is the Python standard library HTML4 name2codepoint mapping used by python-slugify. Java consumes this data with the existing text-unidecode 1.3 resource to reproduce the installed legacy slug algorithm. No Python process executes slug generation or creation.
