#!/usr/bin/env python3
"""Render NavView offline, so the layout can be seen without the simulator.

The Connect IQ simulator's device panel does not render in this environment,
which left the arrow drawing unverifiable. This mirrors `Maneuver.draw` and
`NavView.drawNav` arithmetic exactly - integer division included, since Monkey
C `Number / Number` truncates - and draws the result at the fr745's real
240x240. It is a model, not the app: it catches geometry and layout mistakes
(overlap, clipping, arrows that read wrong), not Monkey C behaviour.

    python3 tools/preview.py out.png
"""
import sys
from PIL import Image, ImageDraw, ImageFont

W = H = 240
FONT_DIR = "/usr/share/fonts/truetype/dejavu"

# Approximate fr745 font heights. Close enough to catch collisions.
FONTS = {
    "XTINY": 18,
    "SMALL": 24,
    "MEDIUM": 28,
    "NUMBER_MEDIUM": 46,
}

# Arrowhead proportions, in multiples of the stroke width.
HEAD_LEN = 1.7
HEAD_HALF = 1.35

GREEN = (0, 255, 0)
WHITE = (255, 255, 255)
LTGRAY = (170, 170, 170)
DKGRAY = (85, 85, 85)
BLUE = (0, 170, 255)
ORANGE = (255, 170, 0)

UNKNOWN, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, \
    SHARP_RIGHT, UTURN, ROUNDABOUT, MERGE, FORK_LEFT, FORK_RIGHT, ARRIVE, \
    OFF_ROUTE = range(15)

NAMES = {
    UNKNOWN: "unknown", STRAIGHT: "straight", LEFT: "left", RIGHT: "right",
    SLIGHT_LEFT: "slight left", SLIGHT_RIGHT: "slight right",
    SHARP_LEFT: "sharp left", SHARP_RIGHT: "sharp right", UTURN: "u-turn",
    ROUNDABOUT: "roundabout", MERGE: "merge", FORK_LEFT: "fork left",
    FORK_RIGHT: "fork right", ARRIVE: "arrive", OFF_ROUTE: "off route",
}


def font(name):
    path = f"{FONT_DIR}/DejaVuSans-Bold.ttf"
    return ImageFont.truetype(path, int(FONTS[name] * 0.78))



def seg(d, x0, y0, x1, y1, t, color):
    """Thick line as a quad, so thickness is exact and matches fillPolygon."""
    dx, dy = x1 - x0, y1 - y0
    L = max((dx * dx + dy * dy) ** 0.5, 0.001)
    nx, ny = -dy / L * (t / 2.0), dx / L * (t / 2.0)
    d.polygon([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
               (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)], fill=color)


def head(d, x, y, dx, dy, t, color):
    """Triangle at (x,y) pointing along (dx,dy)."""
    L = max((dx * dx + dy * dy) ** 0.5, 0.001)
    ux, uy = dx / L, dy / L
    px, py = -uy, ux
    d.polygon([(x + ux * HEAD_LEN * t, y + uy * HEAD_LEN * t),
               (x + px * HEAD_HALF * t, y + py * HEAD_HALF * t),
               (x - px * HEAD_HALF * t, y - py * HEAD_HALF * t)], fill=color)


def draw_maneuver(d, m, cx, cy, size, color):
    """Mirror of Maneuver.draw.

    Every shape is built the same way: decide where the arrow tip goes, put
    the head's base 2t back along the heading, and run the shaft into exactly
    that point. Getting this wrong is what left the heads floating free of
    their shafts in the first cut.
    """
    h = size // 2
    t = size // 6
    if t < 2:
        t = 2
    base_y = cy + h          # where the route enters the icon, bottom centre
    dim = DKGRAY if color != DKGRAY else (45, 45, 45)

    def arrow_to(points, tip_x, tip_y, dx, dy):
        """Run a polyline into an arrowhead whose tip is at (tip_x, tip_y)."""
        L = max((dx * dx + dy * dy) ** 0.5, 0.001)
        ux, uy = dx / L, dy / L
        bx, by = tip_x - ux * HEAD_LEN * t, tip_y - uy * HEAD_LEN * t
        pts = list(points) + [(bx, by)]
        for i in range(len(pts) - 1):
            seg(d, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], t, color)
        head(d, bx, by, ux, uy, t, color)

    if m == STRAIGHT:
        arrow_to([(cx, base_y)], cx, cy - h, 0, -1)

    elif m == MERGE:
        # A lane joining from the right, not just "straight on" - otherwise
        # merge and straight are the same picture.
        arrow_to([(cx, base_y)], cx, cy - h, 0, -1)
        # Joining lane: comes in from the lower right and meets the shaft,
        # drawn in two segments so it reads as a merge rather than a stray
        # tick beside the arrow.
        seg(d, cx + h, base_y, cx + h - t, cy + h // 3, t, color)
        seg(d, cx + h - t, cy + h // 3, cx + t // 2, cy - h // 4, t, color)

    elif m in (LEFT, RIGHT):
        sign = -1 if m == LEFT else 1
        arrow_to([(cx, base_y), (cx, cy)], cx + sign * h, cy, sign, 0)

    elif m in (SLIGHT_LEFT, SLIGHT_RIGHT):
        # A diagonal, not an L: that is what makes it read as "bear left"
        # rather than "turn left" at a glance.
        sign = -1 if m == SLIGHT_LEFT else 1
        arrow_to([(cx, base_y), (cx, cy + h // 4)],
                 cx + sign * h, cy - h + t, sign, -1)

    elif m in (SHARP_LEFT, SHARP_RIGHT):
        # A tight V: up, then straight back down and out. The doubling-back
        # leg has to be long or it reads as a smudge on the shaft.
        sign = -1 if m == SHARP_LEFT else 1
        # Both legs must be long relative to the head, or the head swallows
        # the turn and the whole glyph collapses into a blob.
        knee_x, knee_y = cx, cy - int(h * 0.85)
        tip_x, tip_y = cx + sign * h, cy + int(h * 0.75)
        arrow_to([(cx, base_y), (knee_x, knee_y)],
                 tip_x, tip_y, tip_x - knee_x, tip_y - knee_y)

    elif m in (FORK_LEFT, FORK_RIGHT):
        # Both branches drawn, the taken one solid and headed: a fork is a
        # choice, and showing only one branch loses that.
        sign = -1 if m == FORK_LEFT else 1
        split_y = cy + h // 3
        seg(d, cx, base_y, cx, split_y, t, color)
        # The branch not taken, dimmed: a fork is a choice, and drawing only
        # the chosen side makes it identical to a slight turn.
        seg(d, cx, split_y, cx - sign * h, cy - h + 2 * t, max(t * 2 // 3, 2), dim)
        arrow_to([(cx, split_y)], cx + sign * h, cy - h + 2 * t, sign, -1)

    elif m == UTURN:
        # Up the right side, over the top, back down the left with the head
        # pointing down - the standard reading of a u-turn.
        r = h // 2
        seg(d, cx + r, base_y, cx + r, cy, t, color)
        d.arc([cx - r, cy - r, cx + r, cy + r], 180, 360, fill=color, width=t)
        arrow_to([(cx - r, cy)], cx - r, cy + h - t, 0, 1)

    elif m == ROUNDABOUT:
        # Ring with an entry stub at the bottom and an exit arrow leaving it,
        # rather than a bare circle that reads as a map pin.
        r = int(h * 0.62)
        ring_cy = cy - t // 2
        d.ellipse([cx - r, ring_cy - r, cx + r, ring_cy + r],
                  outline=color, width=max(t // 2, 2))
        seg(d, cx, base_y, cx, ring_cy + r, t, color)
        # Short exit tangent. A long one turns the whole glyph into a symbol
        # that reads as something else entirely.
        # Exit at 3 o'clock pointing right. A 45-degree exit plus a ring and
        # a stem below is, unfortunately, exactly the Mars symbol.
        arrow_to([(cx + r, ring_cy)], cx + r + 3 * t, ring_cy, 1, 0)

    elif m == ARRIVE:
        # Pin: ring on a stem, which reads as a destination marker.
        r = int(h * 0.45)
        py = cy - h // 4
        d.ellipse([cx - r, py - r, cx + r, py + r], outline=color,
                  width=max(t // 2, 2))
        d.ellipse([cx - r // 3, py - r // 3, cx + r // 3, py + r // 3], fill=color)
        seg(d, cx, py + r, cx, base_y, max(t // 2, 2), color)

    elif m == OFF_ROUTE:
        # Two crossed strokes. Deliberately not an arrow: once the route has
        # been left there is no direction to give, and anything arrow-shaped
        # here would be read as one.
        xr = h * 0.72
        seg(d, cx - xr, cy - xr, cx + xr, cy + xr, t, color)
        seg(d, cx + xr, cy - xr, cx - xr, cy + xr, t, color)


def fit(d, text, f, max_w):
    """Mirror of NavView.fit - truncate with an ellipsis."""
    if d.textlength(text, font=f) <= max_w:
        return text
    s = text
    while len(s) > 1 and d.textlength(s + "...", font=f) > max_w:
        s = s[:-1]
    return s + "..."


def wrap(d, text, f, max_w, max_lines):
    """Mirror of NavView.wrap - greedy word wrap, capped."""
    out, line = [], ""
    for word in text.split(" "):
        cand = word if not line else line + " " + word
        if d.textlength(cand, font=f) <= max_w:
            line = cand
        else:
            if line:
                out.append(line)
            line = word
            if len(out) >= max_lines:
                return out
    if line and len(out) < max_lines:
        out.append(line)
    return out


def draw_distance(d, cx, y, text, fg):
    """Mirror of NavView.drawDistance: big number, small unit beside it.

    FONT_NUMBER_MEDIUM on the real device is a digits-and-separators font with
    no letters at all, so drawing "348 m" through it renders "348" and the unit
    silently vanishes. This preview draws everything in DejaVu, which has every
    glyph, so it cheerfully showed "200 m" for months while the watch showed
    "200". Splitting the draw here keeps the geometry honest; UNIT_SAFE below
    keeps the font limitation honest.
    """
    num, _, unit = text.partition(" ")
    nf, uf = font("NUMBER_MEDIUM"), font("SMALL")
    nw = d.textlength(num, font=nf)
    gap = d.textlength(" ", font=uf) if unit else 0
    uw = d.textlength(unit, font=uf) if unit else 0
    x = cx - (nw + gap + uw) / 2
    d.text((x, y), num, font=nf, fill=fg, anchor="la")
    if unit:
        drop = FONTS["NUMBER_MEDIUM"] - FONTS["SMALL"]
        d.text((x + nw + gap, y + drop), unit, font=uf, fill=fg, anchor="la")


def join_footer(left, right):
    """Mirror of NavView.joinFooter."""
    if not left:
        return right
    if not right:
        return left
    return f"{left} \u00b7 {right}"


def render(m, distance, street, eta, remaining="", stale=False):
    """Mirror of NavView.drawNav."""
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)

    fg = DKGRAY if stale else WHITE
    accent = DKGRAY if stale else GREEN
    cx = W // 2

    if m == UNKNOWN:
        # Mirror of NavView: no arrow, so the instruction gets that space
        # rather than being truncated.
        f = font("XTINY")
        lines = wrap(d, street, f, int(W * 0.72), 3)
        lh = FONTS["XTINY"]
        ty = int(H * 0.22) - (len(lines) - 1) * lh // 2
        for line in lines:
            d.text((cx, ty), line, font=f, fill=fg, anchor="ma")
            ty += lh
    else:
        arrow_size = int(H * 0.34)
        draw_maneuver(d, m, cx, int(H * 0.30), arrow_size, accent)

    if distance:
        draw_distance(d, cx, int(H * 0.46), distance, fg)

    if m != UNKNOWN:
        f = font("SMALL")
        d.text((cx, int(H * 0.68)), fit(d, street, f, int(W * 0.82)),
               font=f, fill=fg, anchor="ma")

    f = font("XTINY")
    footer = "no signal" if stale else join_footer(remaining, eta)
    d.text((cx, int(H * 0.80)), footer, font=f,
           fill=ORANGE if stale else LTGRAY, anchor="ma")

    # The screen is round: anything outside this circle is invisible on the
    # watch, which is the failure this preview exists to catch.
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, W - 1, H - 1], fill=255)
    out = Image.new("RGB", (W, H), (25, 25, 25))
    out.paste(img, (0, 0), mask)
    ImageDraw.Draw(out).ellipse([0, 0, W - 1, H - 1], outline=(70, 70, 70))
    return out


def sheet():
    """Every state the watch can display, on one sheet.

    This used to mirror a canned sequence the watch itself could cycle through
    on UP/DOWN; that was removed, because on a live route a stray button press
    swapped the real instruction for a fabricated one. So this is now the only
    way to review the arrows without driving a route."""
    cases = [
        (RIGHT, "200 m", "Rue de Lausanne", "12:34", "8.8 km"),
        (LEFT, "80 m", "Avenue de la Gare", "12:36", "8.6 km"),
        (SLIGHT_LEFT, "400 m", "Route de Berne", "12:41", "7.9 km"),
        (SLIGHT_RIGHT, "1.2 km", "Boulevard de Perolles very long name", "12:45", "6.4 km"),
        (SHARP_LEFT, "50 m", "Chemin des Fleurettes", "12:47", "5.1 km"),
        (SHARP_RIGHT, "30 m", "Rue du Pont", "12:48", "4.8 km"),
        (STRAIGHT, "2.4 km", "Autoroute A12", "12:55", "3.2 km"),
        (UTURN, "90 m", "Route Cantonale", "12:58", "2.6 km"),
        (ROUNDABOUT, "300 m", "Giratoire, 3e sortie", "13:02", "1.9 km"),
        (MERGE, "600 m", "A1 direction Bern", "13:09", "1.4 km"),
        (FORK_LEFT, "700 m", "Sortie 12", "13:12", "980 m"),
        (UNKNOWN, "150 m", "Unrecognised maneuver", "13:15", "700 m"),
        (OFF_ROUTE, "", "Off route", "", ""),
        (ARRIVE, "", "Destination", "13:20", "0 m"),
    ]
    cols, pad = 5, 14
    rows = (len(cases) + cols - 1) // cols
    label_h = 20
    sheet_img = Image.new(
        "RGB",
        (cols * (W + pad) + pad, rows * (H + pad + label_h) + pad),
        (18, 18, 18))
    dd = ImageDraw.Draw(sheet_img)
    lf = ImageFont.truetype(f"{FONT_DIR}/DejaVuSans.ttf", 13)
    for i, (m, dist, street, eta, rem) in enumerate(cases):
        r, c = divmod(i, cols)
        x = pad + c * (W + pad)
        y = pad + r * (H + pad + label_h)
        sheet_img.paste(render(m, dist, street, eta, rem), (x, y))
        dd.text((x + W // 2, y + H + 3), f"{i}  {NAMES[m]}", font=lf,
                fill=(160, 160, 160), anchor="ma")
    return sheet_img


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "preview.png"
    sheet().save(out)
    print("wrote", out)
