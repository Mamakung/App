"""สร้างไอคอนแอปตามวันที่ (12 เดือน x 31 วัน) + activity-alias ใน AndroidManifest
รัน: python3 tools/gen_date_icons.py  (ต้องมี fonttools)"""
import os, re
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main')
RES = os.path.join(ROOT, 'res')
font = TTFont(os.path.join(RES, 'font', 'ibm_plex_sans_thai_bold.ttf'))
gs = font.getGlyphSet(); cmap = font.getBestCmap(); hmtx = font['hmtx']
MONTHS = ["ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."]

def text_path(text, size, cx, baseline):
    s = size / 1000.0
    names = [cmap[ord(ch)] for ch in text]
    width = sum(hmtx[n][0] for n in names) * s
    x = cx - width / 2
    pen = SVGPathPen(gs)
    for n in names:
        gs[n].draw(TransformPen(pen, (s, 0, 0, -s, x, baseline)))
        x += hmtx[n][0] * s
    return pen.getCommands()

HDR = '<?xml version="1.0" encoding="utf-8"?>\n<!-- สร้างโดย tools/gen_date_icons.py ห้ามแก้เอง -->\n'
CARD = 'M34,30h40a6,6 0,0 1,6 6v42a6,6 0,0 1,-6 6h-40a6,6 0,0 1,-6 -6v-42a6,6 0,0 1,6 -6z'
TOP = 'M34,30h40a6,6 0,0 1,6 6v8h-52v-8a6,6 0,0 1,6 -6z'
def vec(paths):
    body = ''.join(f'\n    <path android:fillColor="{c}" android:pathData="{d}" />' for c, d in paths)
    return HDR + f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp"\n    android:viewportWidth="108" android:viewportHeight="108">{body}\n</vector>\n'

d = os.path.join(RES, 'drawable')
for f in os.listdir(d):
    if f.startswith('ic_date_'): os.remove(os.path.join(d, f))
for m, name in enumerate(MONTHS, 1):
    open(os.path.join(d, f'ic_date_month_{m}.xml'), 'w').write(vec([
        ('#FFFFFF', CARD), ('#3A45B8', TOP), ('#FFFFFF', text_path(name, 9, 54, 41))]))
for day in range(1, 32):
    open(os.path.join(d, f'ic_date_day_{day}.xml'), 'w').write(vec([('#1E2430', text_path(str(day), 29, 54, 74))]))
aliases = []
for m in range(1, 13):
    for day in range(1, 32):
        open(os.path.join(d, f'ic_date_fg_{m}_{day}.xml'), 'w').write(HDR +
            '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <item android:drawable="@drawable/ic_date_month_{m}" />\n'
            f'    <item android:drawable="@drawable/ic_date_day_{day}" />\n</layer-list>\n')
        open(os.path.join(d, f'ic_date_{m}_{day}.xml'), 'w').write(HDR +
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            f'    <foreground android:drawable="@drawable/ic_date_fg_{m}_{day}" />\n</adaptive-icon>\n')
        aliases.append(f'''        <activity-alias android:name=".DateIcon_{m}_{day}" android:targetActivity=".MainActivity" android:icon="@drawable/ic_date_{m}_{day}" android:roundIcon="@drawable/ic_date_{m}_{day}" android:enabled="false" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>''')

mp = os.path.join(ROOT, 'AndroidManifest.xml')
s = open(mp).read()
block = ('        <!-- DATE_ICONS_START: ไอคอนตามวันที่ สร้างโดย tools/gen_date_icons.py -->\n'
         '        <activity-alias android:name=".DefaultIcon" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round" android:enabled="true" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>\n'
         + '\n'.join(aliases) + '\n        <!-- DATE_ICONS_END -->\n')
s = re.sub(r'        <!-- DATE_ICONS_START.*?<!-- DATE_ICONS_END -->\n', '', s, flags=re.S)
anchor = '        <provider\n            android:name="androidx.core.content.FileProvider"'
assert anchor in s
s = s.replace(anchor, block + '\n' + anchor, 1)
open(mp, 'w').write(s)
print('ok', len(aliases))
