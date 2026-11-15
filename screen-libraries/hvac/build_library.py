#!/usr/bin/env python3
"""Draws the HVAC library of pictures and graphic components ("Sedona Line") and writes
screen-library.json beside this file. build.py packs that file into libraries/sedona-hvac.jar.

The style is the project's own and deliberately plain, like an engineering drawing: flat fills, no
glow and no gradients, graphite outlines, light equipment bodies on an 80 px module so that pieces
join edge to edge. Colour carries meaning only: grey - off, green - running, red - fault, dashed -
no data, brick for heat, steel blue for cold. Only the SVG subset both editors draw is used: plain
shapes and a rotation animation. Every picture and component has a group ("A/B") for the palette.
"""
import json
from pathlib import Path

STEEL_TOP, STEEL_BOTTOM, EDGE = '#e4e7e9', '#e4e7e9', '#4a5158'
PLATE, PLATE_EDGE = '#f7f7f5', '#2b3035'
INK, WELL, TRACK, PIPE, BRAND = '#2b3035', '#ffffff', '#d5d9dc', '#d0d5d9', '#1f4e79'
OFF, ON, FAULT, UNKNOWN = '#868d94', '#2f7d4f', '#c7302b', '#9aa0a6'
HEAT, COLD, AIR, WATER = '#c2542d', '#2e6da4', '#5f8fb3', '#2e6da4'
ACCENT = {'off': OFF, 'on': ON, 'fault': FAULT, 'unknown': UNKNOWN}
C0, C1 = 16, 64  # the air channel inside an 80 px high duct cell


def svg(w, h, body):
    defs = ('<defs>'
            f'<linearGradient id="steel" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{STEEL_TOP}"/>'
            f'<stop offset="1" stop-color="{STEEL_BOTTOM}"/></linearGradient>'
            f'<linearGradient id="plate" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{PLATE}"/>'
            f'<stop offset="1" stop-color="{PLATE}"/></linearGradient>'
            '</defs>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{defs}{body}</svg>'


def duct(x0, x1, y0=C0, y1=C1):
    """A horizontal run of duct: body, the two walls, a soft highlight."""
    return (f'<rect x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" fill="url(#steel)"/>'
            f'<line x1="{x0}" y1="{y0}" x2="{x1}" y2="{y0}" stroke="{EDGE}" stroke-width="2"/>'
            f'<line x1="{x0}" y1="{y1}" x2="{x1}" y2="{y1}" stroke="{EDGE}" stroke-width="2"/>')


def flange(x, y0=C0, y1=C1):
    return f'<rect x="{x - 2}" y="{y0 - 4}" width="4" height="{y1 - y0 + 8}" rx="1.5" fill="#8b9197" stroke="{EDGE}" stroke-width="1"/>'


def plate(x, y, w, h, state='off', r=9):
    """The dark body of a piece of equipment with a state-coloured rim."""
    dash = ' stroke-dasharray="5 4"' if state == 'unknown' else ''
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
            f'<rect x="{x + 3}" y="{y + 3}" width="{w - 6}" height="{h - 6}" rx="{max(2, r - 3)}" fill="none" stroke="{ACCENT[state]}" stroke-width="2"{dash}/>')


def lamp(x, y, state):
    return f'<circle cx="{x}" cy="{y}" r="4" fill="{ACCENT[state]}" stroke="{PLATE_EDGE}" stroke-width="1"/>'


def chevrons(x, y, colour=AIR, n=3, step=9, flip=False):
    out = ''
    for i in range(n):
        cx = x + i * step
        d = f'M{cx + 5} {y - 6} L{cx} {y} L{cx + 5} {y + 6}' if flip else f'M{cx} {y - 6} L{cx + 5} {y} L{cx} {y + 6}'
        out += f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" stroke-opacity="{0.45 + 0.25 * i:.2f}"/>'
    return out


def spin(cx, cy, body, seconds):
    return (f'<g><animateTransform type="rotate" attributeName="transform" from="0 {cx} {cy}" to="360 {cx} {cy}" '
            f'dur="{seconds}s" repeatCount="indefinite"/>{body}</g>')


def impeller(cx, cy, r, colour, blades=6):
    import math
    out = ''
    for i in range(blades):
        a = 2 * math.pi * i / blades
        b = a + 0.9
        x1, y1 = cx + r * 0.28 * math.cos(a), cy + r * 0.28 * math.sin(a)
        x2, y2 = cx + r * math.cos(b), cy + r * math.sin(b)
        qx, qy = cx + r * 0.75 * math.cos(a + 0.25), cy + r * 0.75 * math.sin(a + 0.25)
        out += f'<path d="M{x1:.1f} {y1:.1f} Q{qx:.1f} {qy:.1f} {x2:.1f} {y2:.1f}" fill="none" stroke="{colour}" stroke-width="4" stroke-linecap="round"/>'
    return out + f'<circle cx="{cx}" cy="{cy}" r="{r * 0.24:.1f}" fill="{colour}"/>'


def states(make, names=('off', 'on', 'fault', 'unknown')):
    return {s: make(s) for s in names}


ASSETS, COMPONENTS = [], []


GROUPS = {'duct': 'Воздух/Воздуховоды', 'intake': 'Воздух/Воздуховоды', 'exhaust': 'Воздух/Воздуховоды', 'silencer': 'Воздух/Воздуховоды',
          'fan': 'Воздух/Движение', 'damper': 'Воздух/Движение', 'vav': 'Воздух/Движение',
          'heater': 'Воздух/Обработка', 'cooler': 'Воздух/Обработка', 'filter': 'Воздух/Обработка', 'humidifier': 'Воздух/Обработка',
          'recuperator': 'Воздух/Утилизация тепла', 'rotary': 'Воздух/Утилизация тепла',
          'pipe': 'Вода/Трубы', 'pump': 'Вода/Арматура', 'valve': 'Вода/Арматура',
          'boiler': 'Вода/Источники', 'chiller': 'Вода/Источники', 'heat': 'Вода/Источники',
          'sensor': 'Измерение/Датчики', 'setpoint': 'Измерение/Уставки', 'tag': 'Оформление', 'zone': 'Оформление'}


def group(id):
    return GROUPS[id.split('-')[0]]


def asset(id, title, w, h, drawings):
    ASSETS.append({'id': 'sl-' + id, 'title': title, 'group': group(id), 'width': w, 'height': h, 'states': drawings})


# ---------------------------------------------------------------- ducts
asset('duct', 'Воздуховод', 80, 80, {'off': svg(80, 80, duct(0, 80))})
asset('duct-long', 'Воздуховод длинный', 240, 80, {'off': svg(240, 80, duct(0, 240) + flange(80) + flange(160))})
asset('duct-flow', 'Воздуховод с потоком', 80, 80, {
    'off': svg(80, 80, duct(0, 80)),
    'on': svg(80, 80, duct(0, 80) + chevrons(24, 40)),
    'unknown': svg(80, 80, duct(0, 80))})
elbow = (f'<path d="M0 {C0} L{C1} {C0} L{C1} 80 L{C0} 80 L{C0} {C1} L0 {C1} Z" fill="url(#steel)"/>'
         f'<path d="M0 {C0} L{C1} {C0} L{C1} 80" fill="none" stroke="{EDGE}" stroke-width="2" stroke-linejoin="round"/>'
         f'<path d="M0 {C1} L{C0} {C1} L{C0} 80" fill="none" stroke="{EDGE}" stroke-width="2" stroke-linejoin="round"/>')
asset('duct-elbow', 'Поворот воздуховода', 80, 80, {'off': svg(80, 80, elbow)})
tee = (duct(0, 80) + f'<rect x="{C0}" y="{C1 - 1}" width="{C1 - C0}" height="{81 - C1}" fill="url(#steel)"/>'
       f'<line x1="{C0}" y1="{C1}" x2="{C0}" y2="80" stroke="{EDGE}" stroke-width="2"/><line x1="{C1}" y1="{C1}" x2="{C1}" y2="80" stroke="{EDGE}" stroke-width="2"/>'
       f'<line x1="{C0 + 1}" y1="{C1}" x2="{C1 - 1}" y2="{C1}" stroke="{STEEL_BOTTOM}" stroke-width="3"/>')
asset('duct-tee', 'Тройник воздуховода', 80, 80, {'off': svg(80, 80, tee)})
louver = duct(28, 80) + f'<rect x="8" y="{C0 - 8}" width="22" height="{C1 - C0 + 16}" rx="4" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
for i in range(5):
    y = C0 - 2 + i * 12
    louver += f'<line x1="12" y1="{y + 6}" x2="26" y2="{y}" stroke="{AIR}" stroke-width="3" stroke-linecap="round"/>'
asset('intake', 'Воздухозабор', 80, 80, {'off': svg(80, 80, louver)})
asset('exhaust', 'Выброс воздуха', 80, 80, {'off': svg(80, 80, duct(0, 52) + f'<rect x="50" y="{C0 - 8}" width="22" height="{C1 - C0 + 16}" rx="4" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>' + chevrons(54, 40, OFF, 2, 8))})

asset('exhaust-left', 'Выброс воздуха влево', 80, 80, {'off': svg(80, 80, duct(28, 80) + f'<rect x="8" y="{C0 - 8}" width="22" height="{C1 - C0 + 16}" rx="4" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>' + chevrons(11, 40, OFF, 2, 8, True))})


# ---------------------------------------------------------------- air handling
def fan(s):
    colour = ACCENT[s]
    wheel = impeller(60, 40, 22, colour)
    body = duct(0, 120) + plate(28, 4, 64, 72, s, 14) + f'<circle cx="60" cy="40" r="27" fill="{WELL}" stroke="{colour}" stroke-width="1.5"/>'
    body += spin(60, 40, wheel, 1.6) if s == 'on' else wheel
    if s == 'on':
        body += chevrons(96, 40)
    return svg(120, 80, body + lamp(82, 14, s))


asset('fan', 'Вентилятор', 120, 80, states(fan))


def damper(s):
    angle = {'off': 0, 'p25': 22, 'p50': 45, 'p75': 68, 'on': 90, 'fault': 30, 'unknown': 45}[s]
    colour = {'off': OFF, 'fault': FAULT, 'unknown': UNKNOWN}.get(s, ON)
    body = duct(0, 80) + f'<rect x="26" y="{C0 - 6}" width="28" height="{C1 - C0 + 12}" rx="5" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
    for cy in (26, 40, 54):
        body += (f'<g transform="rotate({90 - angle} 40 {cy})"><line x1="33" y1="{cy}" x2="47" y2="{cy}" stroke="{colour}" stroke-width="3.5" stroke-linecap="round"/></g>'
                 f'<circle cx="40" cy="{cy}" r="1.6" fill="{INK}"/>')
    return svg(80, 80, body)


asset('damper', 'Заслонка', 80, 80, states(damper, ('off', 'p25', 'p50', 'p75', 'on', 'fault', 'unknown')))


def coil(colour, glyph):
    def draw(s):
        active = colour if s == 'on' else ACCENT[s]
        body = duct(0, 80) + plate(18, 6, 44, 68, s)
        for i in range(4):
            x = 26 + i * 9.5
            body += f'<path d="M{x} 16 q5 6 0 12 q-5 6 0 12 q5 6 0 12 q-5 6 0 12" fill="none" stroke="{active}" stroke-width="3" stroke-linecap="round"/>'
        return svg(80, 80, body + glyph(active))
    return draw


asset('heater', 'Водяной нагреватель', 80, 80, states(coil(HEAT, lambda c: f'<path d="M40 2 L46 8 L34 8 Z" fill="{c}"/>')))
asset('cooler', 'Охладитель', 80, 80, states(coil(COLD, lambda c: f'<path d="M34 2 L46 2 L40 8 Z" fill="{c}"/>')))


def electric(s):
    active = HEAT if s == 'on' else ACCENT[s]
    body = duct(0, 80) + plate(18, 6, 44, 68, s)
    for i in range(3):
        y = 22 + i * 18
        body += f'<path d="M24 {y} l5 -6 l6 12 l6 -12 l6 12 l5 -6" fill="none" stroke="{active}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
    return svg(80, 80, body + f'<path d="M42 0 L36 6 L41 6 L38 11 L45 4 L40 4 Z" fill="{active}"/>')


asset('heater-electric', 'Электронагреватель', 80, 80, states(electric))


def filt(s):
    colour = {'off': '#6b7278', 'on': '#6b7278', 'fault': FAULT, 'unknown': UNKNOWN}[s]
    body = duct(0, 80) + f'<rect x="28" y="{C0 - 6}" width="24" height="{C1 - C0 + 12}" rx="4" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
    body += f'<path d="M34 14 L46 20 L34 26 L46 32 L34 38 L46 44 L34 50 L46 56 L34 62 L46 66" fill="none" stroke="{colour}" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>'
    if s == 'fault':
        body += ''.join(f'<circle cx="{x}" cy="{y}" r="1.8" fill="{FAULT}"/>' for x, y in ((22, 30), (19, 42), (23, 52), (16, 34)))
    return svg(80, 80, body)


asset('filter', 'Фильтр', 80, 80, states(filt))


def humidifier(s):
    active = WATER if s == 'on' else ACCENT[s]
    body = duct(0, 80) + plate(20, 6, 40, 68, s)
    for x, y in ((32, 24), (48, 24), (40, 40), (32, 56), (48, 56)):
        body += f'<path d="M{x} {y - 7} q6 8 0 12 q-6 -4 0 -12 Z" fill="{active}"/>'
    return svg(80, 80, body)


asset('humidifier', 'Увлажнитель', 80, 80, states(humidifier))
silencer = duct(0, 80) + ''.join(f'<rect x="14" y="{y}" width="52" height="8" rx="4" fill="#9aa0a6" stroke="{PLATE_EDGE}" stroke-width="1"/>' for y in (22, 36, 50))
asset('silencer', 'Шумоглушитель', 80, 80, {'off': svg(80, 80, silencer)})

# two-deck pieces: exhaust above (16..64), supply below (112..160)
D0, D1 = 112, 160


def decks(w):
    return duct(0, w) + duct(0, w, D0, D1)


def recuperator(s):
    colour = ACCENT[s]
    body = decks(120) + plate(14, 4, 92, 168, s, 12)
    body += f'<path d="M60 24 L98 88 L60 152 L22 88 Z" fill="{WELL}" stroke="{colour}" stroke-width="2" stroke-linejoin="round"/>'
    for i in range(1, 6):
        t = i / 6
        body += f'<line x1="{60 - 38 * t:.1f}" y1="{24 + 64 * t:.1f}" x2="{98 - 38 * t:.1f}" y2="{88 + 64 * t:.1f}" stroke="{colour}" stroke-width="1.2" stroke-opacity="0.7"/>'
    if s == 'on':
        body += f'<path d="M30 136 L90 40" fill="none" stroke="{HEAT}" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="6 5"/>'
        body += f'<path d="M90 136 L30 40" fill="none" stroke="{COLD}" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="6 5"/>'
    return svg(120, 176, body + lamp(96, 16, s))


asset('recuperator', 'Пластинчатый рекуператор', 120, 176, states(recuperator))


def wheel(s):
    colour = ACCENT[s]
    spokes = ''.join(f'<g transform="rotate({a} 60 88)"><line x1="60" y1="88" x2="60" y2="50" stroke="{colour}" stroke-width="2.5" stroke-linecap="round"/></g>' for a in range(0, 360, 30))
    spokes += f'<circle cx="60" cy="88" r="8" fill="{colour}"/>'
    body = decks(120) + plate(14, 4, 92, 168, s, 12) + f'<circle cx="60" cy="88" r="41" fill="{WELL}" stroke="{colour}" stroke-width="2"/>'
    body += spin(60, 88, spokes, 6) if s == 'on' else spokes
    body += f'<line x1="17" y1="88" x2="103" y2="88" stroke="{PLATE_EDGE}" stroke-width="3"/>'
    return svg(120, 176, body + lamp(96, 16, s))


asset('rotary-wheel', 'Роторный рекуператор', 120, 176, states(wheel))
asset('duct-double', 'Двойной канал', 80, 176, {'off': svg(80, 176, decks(80))})


# ---------------------------------------------------------------- hydronics
def pipe(x0, x1, y=32):
    return (f'<line x1="{x0}" y1="{y}" x2="{x1}" y2="{y}" stroke="{EDGE}" stroke-width="10" stroke-linecap="butt"/>'
            f'<line x1="{x0}" y1="{y}" x2="{x1}" y2="{y}" stroke="{PIPE}" stroke-width="6"/>'
            '')


def pipe_v(y0, y1, x=32):
    return (f'<line x1="{x}" y1="{y0}" x2="{x}" y2="{y1}" stroke="{EDGE}" stroke-width="10"/>'
            f'<line x1="{x}" y1="{y0}" x2="{x}" y2="{y1}" stroke="{PIPE}" stroke-width="6"/>')


asset('pipe', 'Труба', 64, 64, {'off': svg(64, 64, pipe(0, 64))})
asset('pipe-elbow', 'Поворот трубы', 64, 64, {'off': svg(64, 64, pipe(0, 32) + pipe_v(32, 64) + f'<circle cx="32" cy="32" r="5" fill="{EDGE}"/><circle cx="32" cy="32" r="3" fill="{PIPE}"/>')})
asset('pipe-tee', 'Тройник трубы', 64, 64, {'off': svg(64, 64, pipe(0, 64) + pipe_v(34, 64))})


def pump(s):
    colour = ACCENT[s]
    rotor = f'<path d="M32 16 L46 40 L18 40 Z" fill="{colour}"/>'
    body = pipe(0, 64) + f'<circle cx="32" cy="32" r="22" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
    body += f'<circle cx="32" cy="32" r="18" fill="none" stroke="{colour}" stroke-width="2"' + (' stroke-dasharray="5 4"' if s == 'unknown' else '') + '/>'
    rotor = f'<g transform="rotate(90 32 32)">{rotor}</g>'
    body += spin(32, 32, impeller(32, 32, 13, colour, 4), 1.4) if s == 'on' else rotor
    return svg(64, 64, body)


asset('pump', 'Насос', 64, 64, states(pump))


def valve(way3):
    def draw(s):
        level = {'off': 0, 'p25': .25, 'p50': .5, 'p75': .75, 'on': 1, 'fault': 0, 'unknown': 0}[s]
        colour = {'off': OFF, 'fault': FAULT, 'unknown': UNKNOWN}.get(s, ON)
        body = pipe(0, 64, 40) + (pipe_v(40, 64) if way3 else '')
        body += (f'<path d="M12 28 L32 40 L12 52 Z" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5" stroke-linejoin="round"/>'
                 f'<path d="M52 28 L32 40 L52 52 Z" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5" stroke-linejoin="round"/>')
        if way3:
            body += f'<path d="M20 60 L32 40 L44 60 Z" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5" stroke-linejoin="round"/>'
        body += (f'<line x1="32" y1="40" x2="32" y2="20" stroke="{PLATE_EDGE}" stroke-width="2.5"/>'
                 f'<rect x="18" y="4" width="28" height="16" rx="5" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
                 f'<rect x="21" y="9" width="22" height="6" rx="3" fill="{TRACK}"/>')
        if level > 0:
            body += f'<rect x="21" y="9" width="{22 * level:.1f}" height="6" rx="3" fill="{colour}"/>'
        if s in ('fault', 'unknown'):
            body += f'<rect x="19.5" y="5.5" width="25" height="13" rx="4" fill="none" stroke="{colour}" stroke-width="1.5"' + (' stroke-dasharray="4 3"' if s == 'unknown' else '') + '/>'
        return svg(64, 64, body)
    return draw


asset('valve', 'Клапан двухходовой', 64, 64, states(valve(False), ('off', 'p25', 'p50', 'p75', 'on', 'fault', 'unknown')))
asset('valve-3way', 'Клапан трёхходовой', 64, 64, states(valve(True), ('off', 'p25', 'p50', 'p75', 'on', 'fault', 'unknown')))


# ---------------------------------------------------------------- sensors and tags
def sensor(letter, colour):
    def draw(s):
        rim = colour if s in ('off', 'on') else ACCENT[s]
        dash = ' stroke-dasharray="4 3"' if s == 'unknown' else ''
        body = (f'<line x1="20" y1="34" x2="20" y2="60" stroke="{EDGE}" stroke-width="3" stroke-linecap="round"/>'
                f'<circle cx="20" cy="60" r="3" fill="{rim}"/>'
                f'<circle cx="20" cy="20" r="16" fill="url(#plate)" stroke="{PLATE_EDGE}" stroke-width="1.5"/>'
                f'<circle cx="20" cy="20" r="12.5" fill="none" stroke="{rim}" stroke-width="2"{dash}/>'
                f'<text x="20" y="25.5" text-anchor="middle" font-family="sans-serif" font-size="15" font-weight="bold" fill="{INK}">{letter}</text>')
        return svg(40, 64, body)
    return draw


for key, letter, colour, title in (('t', 'T', HEAT, 'Датчик температуры'), ('h', 'H', WATER, 'Датчик влажности'),
                                   ('p', 'P', '#6b5b95', 'Датчик давления'), ('q', 'Q', '#4f7f5a', 'Датчик качества воздуха')):
    asset('sensor-' + key, title, 40, 64, states(sensor(letter, colour), ('off', 'fault', 'unknown')))

tag = ('<rect x="1" y="1" width="94" height="30" rx="15" fill="#ffffff" fill-opacity="0.92" stroke="#51616d" stroke-width="1.5"/>'
       f'<rect x="1" y="1" width="8" height="30" rx="4" fill="{BRAND}"/>')
asset('tag', 'Плашка значения', 96, 32, {'off': svg(96, 32, tag)})


# ---------------------------------------------------------------- plant
def boiler(s):
    colour = HEAT if s == 'on' else ACCENT[s]
    body = plate(8, 8, 104, 124, s, 14) + f'<rect x="26" y="30" width="68" height="52" rx="8" fill="{WELL}" stroke="{colour}" stroke-width="1.5"/>'
    if s == 'on':
        body += f'<path d="M60 38 q16 18 4 36 q-2 -10 -8 -14 q-2 10 -10 14 q-8 -16 14 -36 Z" fill="{HEAT}"/>'
    else:
        body += f'<path d="M60 38 q16 18 4 36 q-2 -10 -8 -14 q-2 10 -10 14 q-8 -16 14 -36 Z" fill="none" stroke="{colour}" stroke-width="2"/>'
    body += pipe(112, 120, 28) + pipe(112, 120, 124)
    body += f'<rect x="30" y="96" width="60" height="8" rx="4" fill="{TRACK}"/>' + lamp(98, 22, s)
    return svg(120, 140, body)


asset('boiler', 'Котёл', 120, 140, states(boiler))


def chiller(s):
    colour = COLD if s == 'on' else ACCENT[s]
    body = plate(8, 8, 144, 104, s, 14)
    fanbody = impeller(46, 52, 20, colour, 5)
    fanbody2 = impeller(100, 52, 20, colour, 5)
    for cx in (46, 100):
        body += f'<circle cx="{cx}" cy="52" r="26" fill="{WELL}" stroke="{colour}" stroke-width="1.5"/>'
    body += (spin(46, 52, fanbody, 1.8) + spin(100, 52, fanbody2, 1.8)) if s == 'on' else fanbody + fanbody2
    body += f'<path d="M132 86 l0 12 M126 89 l12 6 M126 95 l12 -6" stroke="{colour}" stroke-width="2" stroke-linecap="round" fill="none"/>' + lamp(138, 22, s)
    return svg(160, 120, body)


asset('chiller', 'Чиллер', 160, 120, states(chiller))


def exchanger(s):
    colour = ACCENT[s]
    body = plate(14, 8, 52, 136, s, 8)
    for i in range(6):
        x = 22 + i * 7.2
        body += f'<line x1="{x:.1f}" y1="18" x2="{x:.1f}" y2="134" stroke="{HEAT if i % 2 == 0 else COLD}" stroke-width="3" stroke-linecap="round" stroke-opacity="{1 if s == "on" else 0.45}"/>'
    body += pipe(0, 14, 28) + pipe(0, 14, 124) + pipe(66, 80, 28) + pipe(66, 80, 124)
    return svg(80, 152, body)


asset('heat-exchanger', 'Теплообменник', 80, 152, states(exchanger))


def vav(s):
    colour = ACCENT[s]
    body = duct(0, 120) + plate(30, 6, 60, 68, s) + f'<g transform="rotate(-35 60 40)"><line x1="44" y1="40" x2="76" y2="40" stroke="{colour}" stroke-width="4" stroke-linecap="round"/></g><circle cx="60" cy="40" r="2" fill="{INK}"/>'
    body += f'<text x="60" y="70" text-anchor="middle" font-family="sans-serif" font-size="9" font-weight="bold" fill="{colour}">VAV</text>'
    return svg(120, 80, body)


asset('vav', 'VAV-бокс', 120, 80, states(vav))
zone = ('<rect x="2" y="2" width="236" height="156" rx="14" fill="#ffffff" fill-opacity="0.55" stroke="#51616d" stroke-width="2" stroke-dasharray="10 6"/>'
        f'<rect x="2" y="2" width="236" height="26" rx="13" fill="#3a4047"/>')
asset('zone', 'Помещение', 240, 160, {'off': svg(240, 160, zone)})


# ---------------------------------------------------------------- graphic components
def point(id, label, kind, slots):
    return {'id': id, 'label': label, 'kind': kind, 'slots': slots}


def symbol(id, asset_id, x, y, mode='static', state=None, fault=None, rotation=0):
    o = {'id': id, 'assetId': 'sl-' + asset_id, 'label': '', 'x': x, 'y': y, 'rotation': rotation, 'mode': mode}
    if state:
        o['binding'] = {'path': '.', 'slot': state, 'type': 'bool' if mode == 'bool' else 'float'}
    if fault:
        o['fault'] = {'path': '.', 'slot': fault, 'type': 'bool'}
    return o


def value(id, pt, x, y, unit='', decimals=1, width=80, kind='value', label='', bool_point=False, **more):
    w = {'id': id, 'kind': kind, 'label': label or pt, 'path': '.', 'slot': pt, 'type': 'bool' if bool_point else 'float',
         'x': x, 'y': y, 'unit': unit, 'decimals': decimals, 'appearance': 'value', 'width': width,
         'showLabel': bool(label), 'showQuality': False, 'fontSize': 15}
    w.update(more)
    return w


def component(id, title, w, h, points, objects, widgets=()):
    COMPONENTS.append({'id': 'sl-c-' + id, 'title': title, 'group': group(id), 'width': w, 'height': h, 'points': points,
                       'objects': objects, 'widgets': list(widgets)})


RUN = ['run', 'running', 'state', 'on', 'out', 'enable', 'enabled', 'start']
ALARM = ['fault', 'alarm', 'error', 'trip', 'failure']
LEVEL = ['out', 'position', 'pos', 'level', 'value', 'speed', 'in']
VALUE = ['out', 'value', 'pv', 'in', 'temp', 'input']
TAG = dict(x=12, y=73)  # where the value plate sits under an 80 px high picture


def with_tag(w, h, extra=28):
    """Height of a component with a value plate under the picture, and the plate's place in %."""
    total = h + extra + 6
    return total, 100 * (h + 4) / total


def inline(id, title, picture, w, h, points, mode, state, fault, pt, unit, decimals=0, label='', pw=None, back=None, back_w=80, back_y=0):
    """A picture with a value plate under it. A picture narrower than the box (pw) sits in the
    middle on a run of duct or pipe (back), so that components placed side by side join."""
    total, ty = with_tag(w, h)
    tx = 100 * (w - 96) / 2 / w
    px = 100 * (w - (pw or w)) / 2 / w
    behind = []
    if back:
        by = 100 * back_y / total
        behind = [symbol('run1', back, 0, by), symbol('run2', back, 100 * (w - back_w) / w, by)]
    component(id, title, w, total, points,
              behind + [symbol('pic', picture, px, 0, mode, state, fault), symbol('tag', 'tag', tx, ty)],
              [value('val', pt, tx + 100 * 14 / w, ty + 100 * 5 / total, unit, decimals)])


inline('fan', 'Вентилятор: пуск, авария, скорость', 'fan', 120, 80,
       [point('run', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('speed', 'Скорость, %', 'number', ['speed', 'freq', 'out', 'value'])],
       'bool', 'run', 'fault', 'speed', '%')
inline('damper', 'Заслонка: положение', 'damper', 96, 80,
       [point('position', 'Положение 0–1', 'number', LEVEL), point('fault', 'Авария', 'bool', ALARM)],
       'position', 'position', 'fault', 'position', '', 2, pw=80, back='duct')
inline('filter', 'Фильтр: засор, перепад', 'filter', 96, 80,
       [point('dirty', 'Засорён', 'bool', ['dirty', 'alarm', 'fault', 'out']), point('dp', 'Перепад, Па', 'number', ['dp', 'pressure', 'out', 'value'])],
       'static', None, 'dirty', 'dp', 'Па', pw=80, back='duct')
inline('heater', 'Водяной нагреватель: работа, температура', 'heater', 96, 80,
       [point('active', 'Нагрев идёт', 'bool', RUN), point('frost', 'Угроза заморозки', 'bool', ['frost', 'alarm', 'fault']), point('temp', 'Температура, °C', 'number', VALUE)],
       'bool', 'active', 'frost', 'temp', '°C', 1, pw=80, back='duct')
inline('cooler', 'Охладитель: работа, температура', 'cooler', 96, 80,
       [point('active', 'Охлаждение идёт', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('temp', 'Температура, °C', 'number', VALUE)],
       'bool', 'active', 'fault', 'temp', '°C', 1, pw=80, back='duct')
inline('heater-electric', 'Электронагреватель: работа, мощность', 'heater-electric', 96, 80,
       [point('active', 'Включён', 'bool', RUN), point('fault', 'Перегрев', 'bool', ['overheat', 'fault', 'alarm']), point('power', 'Мощность, %', 'number', LEVEL)],
       'bool', 'active', 'fault', 'power', '%', pw=80, back='duct')
inline('humidifier', 'Увлажнитель: работа, влажность', 'humidifier', 96, 80,
       [point('active', 'Включён', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('humidity', 'Влажность, %', 'number', VALUE)],
       'bool', 'active', 'fault', 'humidity', '%', pw=80, back='duct')
inline('recuperator', 'Рекуператор: работа, КПД', 'recuperator', 120, 176,
       [point('active', 'Работа', 'bool', RUN), point('fault', 'Обмерзание', 'bool', ['frost', 'fault', 'alarm']), point('efficiency', 'КПД, %', 'number', ['efficiency', 'eff', 'out', 'value'])],
       'bool', 'active', 'fault', 'efficiency', '%')
inline('rotary-wheel', 'Роторный рекуператор: работа, скорость', 'rotary-wheel', 120, 176,
       [point('run', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('speed', 'Скорость, %', 'number', ['speed', 'out', 'value'])],
       'bool', 'run', 'fault', 'speed', '%')
inline('pump', 'Насос: пуск, авария', 'pump', 96, 64,
       [point('run', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('value', 'Значение', 'number', ['speed', 'pressure', 'out', 'value'])],
       'bool', 'run', 'fault', 'value', '', 1, pw=64, back='pipe', back_w=64)
inline('valve', 'Клапан: положение', 'valve', 96, 64,
       [point('position', 'Положение 0–1', 'number', LEVEL), point('fault', 'Авария', 'bool', ALARM)],
       'position', 'position', 'fault', 'position', '', 2, pw=64, back='pipe', back_w=64, back_y=8)
inline('valve-3way', 'Трёхходовой клапан: положение', 'valve-3way', 96, 64,
       [point('position', 'Положение 0–1', 'number', LEVEL), point('fault', 'Авария', 'bool', ALARM)],
       'position', 'position', 'fault', 'position', '', 2, pw=64, back='pipe', back_w=64, back_y=8)
inline('vav', 'VAV-бокс: расход, заслонка', 'vav', 120, 80,
       [point('active', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('flow', 'Расход, м³/ч', 'number', ['flow', 'out', 'value'])],
       'bool', 'active', 'fault', 'flow', 'м³/ч')
inline('boiler', 'Котёл: работа, авария, подача', 'boiler', 120, 140,
       [point('run', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('supply', 'Подача, °C', 'number', VALUE)],
       'bool', 'run', 'fault', 'supply', '°C', 1)
inline('chiller', 'Чиллер: работа, авария, подача', 'chiller', 160, 120,
       [point('run', 'Работа', 'bool', RUN), point('fault', 'Авария', 'bool', ALARM), point('supply', 'Подача, °C', 'number', VALUE)],
       'bool', 'run', 'fault', 'supply', '°C', 1)
for key, title, unit, decimals in (('t', 'Датчик температуры', '°C', 1), ('h', 'Датчик влажности', '%', 0),
                                   ('p', 'Датчик давления', 'Па', 0), ('q', 'Датчик качества воздуха', 'ppm', 0)):
    component('sensor-' + key, title, 140, 64,
              [point('value', 'Значение', 'number', VALUE), point('fault', 'Обрыв датчика', 'bool', ALARM)],
              [symbol('pic', 'sensor-' + key, 0, 0, 'static', None, 'fault'), symbol('tag', 'tag', 100 * 44 / 140, 100 * 4 / 64)],
              [value('val', 'value', 100 * 58 / 140, 100 * 8 / 64, unit, decimals)])
component('setpoint', 'Уставка и факт', 200, 96,
          [point('setpoint', 'Уставка', 'number', ['setpoint', 'sp', 'in', 'out']), point('actual', 'Факт', 'number', VALUE)],
          [symbol('tag', 'tag', 0, 0)],
          [value('act', 'actual', 7, 100 * 5 / 96, '°C', 1),
           value('set', 'setpoint', 0, 100 * 36 / 96, '°C', 1, 190, 'setpoint', 'Уставка', appearance='compact', showLabel=True)])

library = {'format': 'mcs-sedona-ui-library', 'version': 1, 'id': 'sedona-hvac', 'title': 'Sedona Line · ОВиК',
           'assets': ASSETS, 'components': COMPONENTS}
out = Path(__file__).resolve().parent / 'screen-library.json'
out.write_text(json.dumps(library, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print(f'{out}: {len(ASSETS)} pictures, {len(COMPONENTS)} components, {out.stat().st_size // 1024} KiB')
