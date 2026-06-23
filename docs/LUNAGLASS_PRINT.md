# LunaGlass — Print Translation Notes

**Author:** Chad
**Date:** 2026-05-31
**Status:** NOTES — captured from conversation, no production decisions yet

---

## Premise

The Project Aether books (Coherence Paradigm, Superfluid Theory of Reality, any future titles) should read as physical extensions of the LunaGlass design language used in Aegis, LunaOS, and every Aether app. They should not look like a separate book line. They should look like the rest of the project, in paper.

## Constraint

LunaGlass is emissive: dark glass surfaces, cyan strokes that glow on a backlit display. Print is reflective. Translating the language to paper is not 1:1 — it requires a materials reinterpretation. Glow becomes foil or metallic ink. Glass becomes matte coated stock. Stroke becomes deboss or letterpress impression.

## Cover / Jacket / Spine

Full LunaGlass treatment.

- **Stock** — matte coated black. Soft-touch lamination if budget allows; depth comes from the lack of reflectivity, not gloss. Avoid high-gloss black — it reads cheap and shows fingerprints.
- **Cyan accent** — foil stamp. A real PMS cyan, not a "process cyan" approximation. Sparingly used: title, single hex element, perhaps a thin rule under the title.
- **Hex geometry** — emboss or deboss. Tactile, not luminous. The same hex shapes Aegis uses for avatars and the bottom nav, translated to a physical impression in the cover stock.
- **Typography** — title set in the LunaGlass monospace family, large, generously letter-spaced. Author name beneath in a lighter weight of the same face.
- **Spine** — hex bar in cyan foil at the head, title set vertically in monospace below, author name at the foot. The bar reads as a brand mark from the shelf.

## Interior

Book-standard substrate, LunaGlass details. Reading 300 pages of dense theory in white-on-black is brutal on the eyes and the print economics don't work at book scale. LunaGlass on the interior is in the typographic discipline, not the substrate.

- **Stock** — uncoated cream or white book paper, ~80 GSM. Standard.
- **Body type** — a readable serif (Equity, EB Garamond, similar). NOT the LunaGlass monospace — monospace at body-text scale destroys reading speed.
- **Headers and running text in monospace** — chapter titles, running heads, page numbers, captions, equation labels. This is where LunaGlass shows up consistently.
- **Cyan as second ink** — axis labels on diagrams, equation callouts, pull-quote rules, small flourishes around chapter openers. Sparingly. The book should still read fine if printed single-colour, with cyan being a quality upgrade rather than load-bearing.
- **Chapter openers** — large hex tile holding the chapter number in cyan monospace. The body text below starts in serif. The hex tile is the transition between LunaGlass and reading-mode.
- **Section dividers** — thin cyan rule or hex tiling, not the standard centred asterism. Reads as a deliberate technical signal.
- **Page numbers** — set inside a small hex outline at the outer margin. Tiny, monospace, cyan.

## Materials list (for a printer brief)

- Cover stock: matte coated black, ~300 GSM
- Cover decoration: cyan foil + emboss/deboss
- Interior stock: cream uncoated book paper, ~80 GSM
- Interior inks: black + PMS cyan (one second-colour pass)
- Bind: smyth-sewn case bind for the canonical editions; perfect bind acceptable for paperback runs

## Typography candidates

Monospace family: same one LunaGlass uses on screen. If unavailable in print-quality form, fall back to JetBrains Mono or IBM Plex Mono — both have proper print weights and ligatures.

Serif body: Equity (Matthew Butterick), EB Garamond, Adobe Caslon Pro. Whichever pairs cleanest with the monospace at small sizes.

## What's not decided

- Whether the four-component frame on the About page should carry through to a four-book set, or whether SfTR + Coherence Paradigm + future titles are each printed independently with no series branding.
- Whether the LunaGlass monospace is licensed for print embedding (need to check the licence).
- Whether to use a dust jacket (LunaGlass treatment) over plain cloth boards, or skip the jacket and treat the boards directly.
- Print run, format (octavo / royal / something else), and budget. These determine which of the above are achievable.

## Open question for the author

Is there a canonical SfTR paper to cite on the SfTR bullet in the app About page? Currently only LunaOS carries a citation (Coherence Paradigm). SfTR stands alone. If there's a published paper that anchors it the same way CP anchors LunaOS, the symmetry would tighten.
