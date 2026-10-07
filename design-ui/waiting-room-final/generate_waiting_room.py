#!/usr/bin/env python3
"""Generate editable, landscape-first Estemshan Waiting Room SVG states.

The screens are vector-first so they can be imported into Figma as editable groups.
They intentionally avoid HTML and raster UI artwork.
"""
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'waiting_room_final'
OUT.mkdir(parents=True, exist_ok=True)
W, H = 970, 450
BG = '#0D0A07'
SURFACE = '#17130E'
SURFACE_HI = '#1D1912'
GOLD = '#E8A33D'
BRASS = '#A8742A'
IVORY = '#F0EADA'
MUTED = '#A89F8E'
DIM = '#756D60'


def e(s):
    return escape(str(s), {'"': '&quot;'})


def rect(x, y, w, h, rx, fill, stroke='none', sw=1, extra=''):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" {extra}/>'


def text(x, y, s, size=12, fill=IVORY, family='Saira, Arial, sans-serif', weight=400, spacing='0.02em', extra=''):
    return f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" letter-spacing="{spacing}" fill="{fill}" {extra}>{e(s)}</text>'


def line(x1, y1, x2, y2, stroke=GOLD, sw=1, extra=''):
    return f'<path d="M{x1} {y1} L{x2} {y2}" fill="none" stroke="{stroke}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round" {extra}/>'


def circle(x, y, r, fill='none', stroke='none', sw=1, extra=''):
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" {extra}/>'


def chair(cx, cy, tag):
    """A small standalone chair silhouette, deliberately not an empty player card."""
    return f'''<g id="Open_Chair_{tag}" stroke-linecap="round" stroke-linejoin="round">
      <path d="M{cx-10} {cy-17}v10q0 4 4 4h12q4 0 4-4v-10q0-4-4-4h-12q-4 0-4 4Z" fill="#17130E" stroke="{BRASS}" stroke-width="1.6"/>
      <path d="M{cx-14} {cy-2}q0-3 3-3h22q3 0 3 3v5q0 3-3 3h-22q-3 0-3-3Z" fill="#23190F" stroke="{GOLD}" stroke-opacity=".68" stroke-width="1.4"/>
      <path d="M{cx-10} {cy+6}l-3 9 M{cx+10} {cy+6}l3 9" fill="none" stroke="{BRASS}" stroke-width="1.5"/>
      <circle cx="{cx}" cy="{cy-11}" r="1.6" fill="{GOLD}" opacity=".8"/>
      <text x="{cx}" y="{cy+28}" text-anchor="middle" font-family="Spline Sans Mono, monospace" font-size="8" font-weight="600" letter-spacing=".08em" fill="{IVORY}">{e(tag)} · OPEN</text>
      <text x="{cx}" y="{cy+40}" text-anchor="middle" font-family="Saira, Arial, sans-serif" font-size="8" font-weight="600" letter-spacing=".07em" fill="{GOLD}">INVITE</text>
    </g>'''


def player_card(x, y, w, h, seat, name, initial, ready=False, host=False):
    status = '✓ READY' if ready else 'NOT READY'
    status_fill = GOLD if ready else MUTED
    crown = f'<path d="M{x+w-23} {y+17}l4 3 4-7 4 7 4-3-2 10h-14Z" fill="none" stroke="{GOLD}" stroke-width="1.1"/>' if host else ''
    badge = 'YOU' if seat == 'YOU' else seat
    return f'''<g id="Seat_{seat.replace(' ', '_')}_{name}">
      {rect(x, y, w, h, 11, '#17130E', '#8D6429', 1, 'stroke-opacity=".58"')}
      <circle cx="{x+23}" cy="{y+h/2}" r="14" fill="#29231A" stroke="{GOLD}" stroke-opacity=".65" stroke-width="1.2"/>
      {text(x+23, y+h/2+4, initial, 11, IVORY, 'Marcellus, Georgia, serif', 600, '0', 'text-anchor="middle"')}
      {text(x+44, y+19, f'{badge} · {name}', 9, IVORY, 'Saira, Arial, sans-serif', 700, '.02em')}
      {text(x+44, y+35, status, 7, status_fill, 'Spline Sans Mono, monospace', 600, '.05em')}
      {crown}
    </g>'''


def header(occupied, mode='room'):
    if mode == 'room':
        title = 'WAITING ROOM'
        sub = 'PRIVATE CAIRO TABLE'
    else:
        title = 'THE HAND BEGINS'
        sub = 'ESTEMSHAN · MATCH ENTRY'
    return f'''<g id="Room_Header">
      {rect(25, 20, 75, 28, 8, '#17130E', '#765526', .8, 'stroke-opacity=".65"')}
      <path d="M43 34h-11m0 0 5-5m-5 5 5 5" fill="none" stroke="{GOLD}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      {text(55, 38, 'LEAVE', 8, IVORY, 'Spline Sans Mono, monospace', 600, '.08em')}
      {text(123, 34, title, 19, IVORY, 'Marcellus, Georgia, serif', 400, '.14em')}
      {text(124, 51, sub, 7, MUTED, 'Spline Sans Mono, monospace', 500, '.16em')}
      {rect(330, 23, 42, 24, 12, '#241B10', '#A8742A', .8)}
      {text(351, 39, f'{occupied}/4', 9, GOLD, 'Spline Sans Mono, monospace', 700, '.02em', 'text-anchor="middle"')}
      {rect(627, 21, 152, 29, 8, '#17130E', '#574122', .75)}
      {text(638, 32, 'ROOM CODE', 6, MUTED, 'Spline Sans Mono, monospace', 600, '.1em')}
      {text(638, 44, 'X7K2PQ', 10, GOLD, 'Spline Sans Mono, monospace', 700, '.13em')}
      {rect(786, 21, 77, 29, 8, '#17130E', '#574122', .75)}
      <path d="M800 31h9v9h-9z M802 29h9v9" fill="none" stroke="{GOLD}" stroke-width="1"/>
      {text(821, 39, 'COPY', 7, IVORY, 'Spline Sans Mono, monospace', 600, '.06em')}
      {rect(869, 21, 76, 29, 8, '#17130E', '#574122', .75)}
      <path d="M884 32l7-4 7 4v6l-7 4-7-4z M891 28v9 M884 32l7 4 7-4" fill="none" stroke="{GOLD}" stroke-width=".9"/>
      {text(905, 39, 'SHARE', 7, IVORY, 'Spline Sans Mono, monospace', 600, '.05em')}
      <path d="M25 62H945" stroke="{GOLD}" stroke-opacity=".22"/>
    </g>'''


def tabletop(match=False):
    center = 'MATCH TABLE' if match else 'PRIVATE TABLE'
    ornament = '''<g id="Center_Card_Deck" transform="translate(485 251)">
      <rect x="-20" y="-25" width="29" height="42" rx="3" transform="rotate(-14)" fill="#1B140D" stroke="#A8742A" stroke-width="1.2"/>
      <rect x="-6" y="-28" width="29" height="42" rx="3" transform="rotate(10)" fill="#21180E" stroke="#E8A33D" stroke-width="1.3"/>
      <path d="M8-18l8 8-8 8-8-8Z" fill="none" stroke="#E8A33D" stroke-width="1.2"/>
      <circle cx="8" cy="-10" r="2" fill="#E8A33D"/>
    </g>'''
    return f'''<g id="Club_Table">
      <ellipse cx="485" cy="249" rx="218" ry="95" fill="url(#tableWood)" stroke="url(#goldEdge)" stroke-width="3"/>
      <ellipse cx="485" cy="249" rx="205" ry="84" fill="none" stroke="#E8A33D" stroke-opacity=".36" stroke-width="1.1"/>
      <ellipse cx="485" cy="249" rx="185" ry="67" fill="none" stroke="#D6B06E" stroke-opacity=".13" stroke-width=".9"/>
      <path d="M312 249c50-26 101-39 173-39s123 13 173 39c-50 25-101 38-173 38s-123-13-173-38Z" fill="none" stroke="#F0EADA" stroke-opacity=".075" stroke-width="1"/>
      {text(485, 189, center, 6, '#D9B97E', 'Spline Sans Mono, monospace', 600, '.22em', 'text-anchor="middle"')}
      {ornament}
      {text(485, 309, 'GOOD COMPANY · GREAT HANDS', 7, '#D9B97E', 'Marcellus, Georgia, serif', 400, '.11em', 'text-anchor="middle"')}
    </g>'''


def base_svg(title):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" aria-label="{e(title)}">
    <title>{e(title)}</title>
    <defs>
      <linearGradient id="tableWood" x1="0" y1="0" x2="0.9" y2="1"><stop stop-color="#4A2B17"/><stop offset=".48" stop-color="#2A1A10"/><stop offset="1" stop-color="#120E0A"/></linearGradient>
      <linearGradient id="goldEdge" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#F2CF83"/><stop offset=".48" stop-color="#E8A33D"/><stop offset="1" stop-color="#8B5A25"/></linearGradient>
      <radialGradient id="ambient" cx="50%" cy="48%" r="72%"><stop stop-color="#75451E" stop-opacity=".22"/><stop offset=".7" stop-color="#27190F" stop-opacity=".11"/><stop offset="1" stop-color="#0D0A07" stop-opacity="0"/></radialGradient>
      <radialGradient id="entryGlow" cx="50%" cy="42%" r="52%"><stop stop-color="#E8A33D" stop-opacity=".24"/><stop offset="1" stop-color="#E8A33D" stop-opacity="0"/></radialGradient>
      <pattern id="grain" width="36" height="36" patternUnits="userSpaceOnUse"><path d="M3 7h1M19 4h1M30 18h1M8 29h1M26 32h1" stroke="#E8A33D" stroke-opacity=".08" stroke-width="1"/><path d="M12 17h4M24 10h3M4 22h3" stroke="#F0EADA" stroke-opacity=".025" stroke-width="1"/></pattern>
      <filter id="glow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="8" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    </defs>
    <g id="Screen_Background">
      {rect(0, 0, W, H, 0, BG)}
      <rect x="0" y="0" width="970" height="450" fill="url(#ambient)"/>
      <rect x="0" y="0" width="970" height="450" fill="url(#grain)" opacity=".5"/>
      {rect(10, 10, 950, 430, 18, 'none', '#6D512B', .9, 'stroke-opacity=".36"')}
      <path d="M28 74H942" stroke="#E8A33D" stroke-opacity=".08"/>
    </g>'''


def readiness_footer(occupied, ready_count, ready, all_ready=False, starting=False):
    if starting:
        title = 'Starting the match…'
        sub = 'Everyone currently seated is ready.'
    elif all_ready:
        title = 'All players ready'
        sub = 'The table is set. Match begins automatically.'
    else:
        title = f'{ready_count}/{occupied} players ready'
        sub = 'Waiting for everyone at the table.' if occupied > 1 else 'Waiting for friends to join your table.'
    button_text = 'CANCEL READY' if ready else 'READY UP'
    button_fill = '#20180F' if ready else GOLD
    button_text_fill = GOLD if ready else '#211608'
    button_stroke = BRASS if ready else GOLD
    return f'''<g id="Readiness_Footer">
      <path d="M28 384H942" stroke="#E8A33D" stroke-opacity=".18"/>
      {text(40, 404, title, 11, GOLD if (all_ready or starting) else IVORY, 'Marcellus, Georgia, serif', 400, '.035em')}
      {text(40, 420, sub, 8, MUTED, 'Saira, Arial, sans-serif', 500, '.02em')}
      {rect(743, 393, 198, 34, 9, button_fill, button_stroke, .9)}
      {text(842, 414, button_text, 9, button_text_fill, 'Spline Sans Mono, monospace', 700, '.08em', 'text-anchor="middle"')}
    </g>'''


def room_screen(name, occupied, players, ready_count, ready=False, all_ready=False, starting=False):
    parts = [base_svg(name), header(occupied), tabletop()]
    # Required seat placement: P2 left, P3 top, P4 right, YOU bottom.
    specs = {
        'P2': (38, 226, 188, 54, 135, 213, 'P2'),
        'P3': (394, 94, 182, 52, 485, 91, 'P3'),
        'P4': (744, 226, 188, 54, 835, 213, 'P4'),
        'YOU': (391, 323, 188, 54, 485, 321, 'YOU'),
    }
    order = ['P3', 'P2', 'P4', 'YOU']
    for seat in order:
        x,y,w,h,cx,cy,tag = specs[seat]
        player = players.get(seat)
        if player:
            n, initial, is_ready, is_host = player
            parts.append(player_card(x,y,w,h,seat,n,initial,is_ready,is_host))
        else:
            parts.append(chair(cx,cy,tag))
    if starting:
        parts.append('<ellipse cx="485" cy="249" rx="224" ry="101" fill="url(#entryGlow)" filter="url(#glow)" opacity=".65"/>')
    parts.append(readiness_footer(occupied, ready_count, ready, all_ready, starting))
    if all_ready or starting:
        # Quiet pulse-like concentric brass rings; no countdown or host Start control.
        parts.append(f'<g id="Automatic_Start_Rings" opacity=".5"><circle cx="485" cy="249" r="47" fill="none" stroke="{GOLD}" stroke-opacity=".32"/><circle cx="485" cy="249" r="58" fill="none" stroke="{GOLD}" stroke-opacity=".12"/></g>')
    parts.append('</svg>')
    return ''.join(parts)


def match_entry():
    s = [base_svg('Match entry')]
    s.append('<rect x="0" y="0" width="970" height="450" fill="url(#entryGlow)" opacity=".9"/>')
    s.append(header(4, mode='match'))
    s.append(tabletop(match=True))
    s.append(text(485, 116, 'CAIRO CLUB · HAND 01', 8, GOLD, 'Spline Sans Mono, monospace', 600, '.2em', 'text-anchor="middle"'))
    s.append(text(485, 143, 'The table is yours.', 21, IVORY, 'Marcellus, Georgia, serif', 400, '.03em', 'text-anchor="middle"'))
    s.append(text(485, 163, 'A new hand begins.', 10, MUTED, 'Saira, Arial, sans-serif', 500, '.04em', 'text-anchor="middle"'))
    # Subtle four-seat lineup, initials only; no gameplay rules or fabricated score.
    mini = [('P2','O',205,252),('P3','N',405,252),('P4','Y',565,252),('YOU','K',765,252)]
    for label,initial,cx,cy in mini:
        s.append(f'<g id="Match_Seat_{label}">{circle(cx,cy,20,"#1D1912",BRASS,1.2)}{text(cx,cy+5,initial,13,IVORY,"Marcellus, Georgia, serif",600,"0","text-anchor=\"middle\"")}{text(cx,cy+38,label,7,GOLD,"Spline Sans Mono, monospace",600,".08em","text-anchor=\"middle\"")}</g>')
    s.append(f'<g id="Entry_Card" filter="url(#glow)">{rect(459, 202, 52, 73, 6, "#21180E", GOLD, 1.4)}<path d="M485 216l13 13-13 13-13-13z M485 241v17" fill="none" stroke="{GOLD}" stroke-width="1.3"/><circle cx="485" cy="229" r="2.2" fill="{GOLD}"/></g>')
    s.append(f'<path d="M28 384H942" stroke="{GOLD}" stroke-opacity=".18"/>')
    s.append(text(40, 407, 'MATCH ENTRY', 10, GOLD, 'Marcellus, Georgia, serif', 400, '.13em'))
    s.append(text(40, 422, 'The next screen is the live table.', 8, MUTED, 'Saira, Arial, sans-serif', 500, '.02em'))
    s.append('</svg>')
    return ''.join(s)


STATES = [
    ('01_host_only_1_of_4.svg', 'Host only · 1/4', 1,
     {'YOU': ('Khaled_X','K',False,True)}, 0, False, False, False),
    ('02_two_of_four.svg', 'Two players · 2/4', 2,
     {'P2': ('Omar_K','O',False,False), 'YOU': ('Khaled_X','K',False,True)}, 0, False, False, False),
    ('03_three_of_four.svg', 'Three players · 3/4', 3,
     {'P2': ('Omar_K','O',True,False), 'P3': ('Noura_A','N',False,False), 'YOU': ('Khaled_X','K',False,True)}, 1, False, False, False),
    ('04_four_of_four.svg', 'Four players · 4/4', 4,
     {'P2': ('Omar_K','O',True,False), 'P3': ('Noura_A','N',False,False), 'P4': ('Youssef_M','Y',True,False), 'YOU': ('Khaled_X','K',False,True)}, 2, False, False, False),
    ('05_you_ready.svg', 'You ready · 3/4 ready', 4,
     {'P2': ('Omar_K','O',True,False), 'P3': ('Noura_A','N',True,False), 'P4': ('Youssef_M','Y',False,False), 'YOU': ('Khaled_X','K',True,True)}, 3, True, False, False),
    ('06_all_ready_starting.svg', 'All ready · Starting the match', 4,
     {'P2': ('Omar_K','O',True,False), 'P3': ('Noura_A','N',True,False), 'P4': ('Youssef_M','Y',True,False), 'YOU': ('Khaled_X','K',True,True)}, 4, True, True, True),
]

for filename, title, occupied, players, ready_count, ready, all_ready, starting in STATES:
    svg = room_screen(title, occupied, players, ready_count, ready, all_ready, starting)
    (OUT / filename).write_text(svg, encoding='utf-8')

(OUT / '07_match_entry.svg').write_text(match_entry(), encoding='utf-8')

readme = '''# Estemshan Waiting Room — Refined SVG States

These standalone 970 × 450 SVGs are vector-first, editable after Figma import, and designed for a landscape Android screen. They preserve the charcoal/gold/ivory palette and Marcellus/Saira/monospace hierarchy, use the required seat positions (P2 left, P3 top, P4 right, YOU bottom), depict open seats as chair silhouettes, and contain no host Start button.

Files: host-only 1/4, 2/4, 3/4, 4/4, you-ready (3/4 ready), all-ready/Starting the match, and match entry. Room code is the source-valid sample `X7K2PQ`.

In the verified Figma Flow 1, click-through snapshots simulate occupancy changes. You ready auto-advances after 800 ms to the all-ready Starting match state. Starting match then auto-advances to Match entry after an 800 ms delay with a 300 ms Dissolve (about 1.1 s total). Figma is visual-only here; it does not connect to live multiplayer presence or backend readiness.

Source-derived behavior is documented in the parent project’s `WAITING_ROOM_SOURCE_NOTES.md`.
'''
(OUT / 'README.md').write_text(readme, encoding='utf-8')
print(f'Wrote {len(STATES)+1} SVG states to {OUT}')
for path in sorted(OUT.glob('*.svg')):
    print(f'{path.name}: {path.stat().st_size} bytes')
