#!/usr/bin/env python3
"""Exercita a UI real por XTest; precisa de Xvfb, Pillow e fixture npm instalada."""
import argparse
import ctypes as C
import json
import os
from pathlib import Path
import re
import subprocess
import time
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]

class Attributes(C.Structure):
    _fields_ = [('x',C.c_int),('y',C.c_int),('width',C.c_int),('height',C.c_int),('border_width',C.c_int),('depth',C.c_int),('visual',C.c_void_p),('root',C.c_ulong),('klass',C.c_int),('bit_gravity',C.c_int),('win_gravity',C.c_int),('backing_store',C.c_int),('backing_planes',C.c_ulong),('backing_pixel',C.c_ulong),('save_under',C.c_int),('colormap',C.c_ulong),('map_installed',C.c_int),('map_state',C.c_int),('all_event_masks',C.c_long),('your_event_mask',C.c_long),('do_not_propagate_mask',C.c_long),('override_redirect',C.c_int),('screen',C.c_void_p)]
class XImage(C.Structure):
    _fields_ = [('width',C.c_int),('height',C.c_int),('xoffset',C.c_int),('format',C.c_int),('data',C.c_void_p),('byte_order',C.c_int),('bitmap_unit',C.c_int),('bitmap_bit_order',C.c_int),('bitmap_pad',C.c_int),('depth',C.c_int),('bytes_per_line',C.c_int),('bits_per_pixel',C.c_int)]

class Desktop:
    def __init__(self, display):
        self.x=C.CDLL('libX11.so.6'); self.xt=C.CDLL('libXtst.so.6'); x=self.x
        signatures = {
            'XOpenDisplay': ([C.c_char_p],C.c_void_p),
            'XDefaultRootWindow': ([C.c_void_p],C.c_ulong),
            'XQueryTree': ([C.c_void_p,C.c_ulong,C.POINTER(C.c_ulong),C.POINTER(C.c_ulong),C.POINTER(C.POINTER(C.c_ulong)),C.POINTER(C.c_uint)],C.c_int),
            'XFetchName': ([C.c_void_p,C.c_ulong,C.POINTER(C.c_char_p)],C.c_int),
            'XGetWindowAttributes': ([C.c_void_p,C.c_ulong,C.POINTER(Attributes)],C.c_int),
            'XFree': ([C.c_void_p],C.c_int),
            'XSync': ([C.c_void_p,C.c_int],C.c_int),
            'XSetInputFocus': ([C.c_void_p,C.c_ulong,C.c_int,C.c_ulong],C.c_int),
            'XWarpPointer': ([C.c_void_p,C.c_ulong,C.c_ulong,C.c_int,C.c_int,C.c_uint,C.c_uint,C.c_int,C.c_int],C.c_int),
            'XStringToKeysym': ([C.c_char_p],C.c_ulong),
            'XKeysymToKeycode': ([C.c_void_p,C.c_ulong],C.c_uint),
            'XResizeWindow': ([C.c_void_p,C.c_ulong,C.c_uint,C.c_uint],C.c_int),
            'XGetImage': ([C.c_void_p,C.c_ulong,C.c_int,C.c_int,C.c_uint,C.c_uint,C.c_ulong,C.c_int],C.POINTER(XImage)),
            'XDestroyImage': ([C.POINTER(XImage)],C.c_int),
        }
        for name,(args,result) in signatures.items():
            getattr(x,name).argtypes=args; getattr(x,name).restype=result
        self.xt.XTestFakeKeyEvent.argtypes=[C.c_void_p,C.c_uint,C.c_int,C.c_ulong]
        self.xt.XTestFakeButtonEvent.argtypes=[C.c_void_p,C.c_uint,C.c_int,C.c_ulong]
        self.d=x.XOpenDisplay(display.encode()); assert self.d, 'Display X11 indisponível'
        self.root=x.XDefaultRootWindow(self.d)
    def mapped(self):
        root=C.c_ulong(); parent=C.c_ulong(); children=C.POINTER(C.c_ulong)(); count=C.c_uint(); found=None
        self.x.XQueryTree(self.d,self.root,C.byref(root),C.byref(parent),C.byref(children),C.byref(count))
        for i in range(count.value):
            name=C.c_char_p()
            if self.x.XFetchName(self.d,children[i],C.byref(name)) and name.value:
                title=name.value.decode(errors='replace'); self.x.XFree(C.cast(name,C.c_void_p))
                if title == 'Zaplaud':
                    a=Attributes(); self.x.XGetWindowAttributes(self.d,children[i],C.byref(a))
                    if a.map_state == 2: found=(children[i],a.width,a.height)
        if children: self.x.XFree(children)
        return found
    def image(self):
        wid,w,h=self.mapped(); raw=self.x.XGetImage(self.d,wid,0,0,w,h,0xffffffff,2)
        assert raw, 'Falha na captura X11'
        image=Image.frombytes('RGB',(w,h),C.string_at(raw.contents.data,raw.contents.bytes_per_line*h),'raw','BGRX',raw.contents.bytes_per_line)
        self.x.XDestroyImage(raw); return image
    def screenshot(self,name):
        image=self.image(); path=f'/tmp/zaplaud-rs-shell-{name}.png'; image.save(path); return image
    def click(self,px,py):
        wid=self.mapped()[0]; self.x.XSetInputFocus(self.d,wid,1,0)
        self.x.XWarpPointer(self.d,0,wid,0,0,0,0,px,py)
        for pressed in [1,0]: self.xt.XTestFakeButtonEvent(self.d,1,pressed,0)
        self.x.XSync(self.d,0); time.sleep(.15)
    def key(self,name,modifiers=()):
        code=lambda name:self.x.XKeysymToKeycode(self.d,self.x.XStringToKeysym(name.encode()))
        keys=[code(name) for name in modifiers]; target=code(name); assert target, name
        for key in keys: self.xt.XTestFakeKeyEvent(self.d,key,1,0)
        for pressed in [1,0]: self.xt.XTestFakeKeyEvent(self.d,target,pressed,0)
        for key in reversed(keys): self.xt.XTestFakeKeyEvent(self.d,key,0,0)
        self.x.XSync(self.d,0); time.sleep(.025)
    def fill(self,y,text):
        self.click(140,y); self.key('a',('Control_L',))
        symbols={':':('colon',('Shift_L',)),'/':('slash',()),'.':('period',()),'-':('minus',())}
        for char in text:
            name,modifiers=symbols.get(char,(char,())); self.key(name,modifiers)
        time.sleep(.2)
    def resize(self,width):
        self.x.XResizeWindow(self.d,self.mapped()[0],width,760); self.x.XSync(self.d,0); time.sleep(.4)

def wait_for(condition, label, seconds=15):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if condition(): return
        time.sleep(.05)
    raise AssertionError(label)

def palette(theme):
    baseline=json.loads((ROOT/'zaplaud-rs/crates/zaplaud-ui/theme/m3-baseline.json').read_text())[theme]
    kotlin=(ROOT/'android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt').read_text()
    start=kotlin.index(theme+'ColorScheme(')+len(theme+'ColorScheme('); depth=1; end=start
    while depth:
        if kotlin[end]=='(': depth+=1
        if kotlin[end]==')': depth-=1
        end+=1
    for role,hexadecimal,named in re.findall(r'(\w+)\s*=\s*Color(?:\(0x([A-Fa-f0-9]+)\)|\.(White|Black))',kotlin[start:end]):
        baseline[role]='#'+(hexadecimal if hexadecimal else ('FFFFFFFF' if named=='White' else 'FF000000'))
    return {role:tuple(bytes.fromhex(value[3:9])) for role,value in baseline.items()}

class Fixture:
    def __init__(self,port):
        self.port=port; self.start()
    def start(self):
        self.process=subprocess.Popen(['node','--import','tsx','scripts/android-smoke.ts','--port',str(self.port),'--allow','terminal,messages,permissions'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        line=self.process.stdout.readline(); self.ready=json.loads(line); assert self.ready['ready']
    def stop(self):
        self.process.terminate(); self.process.wait(timeout=10)
    def restart(self):
        self.stop(); self.start()

def memory(pid):
    return {line.split(':')[0].lower()+'_kib':int(line.split()[1]) for line in Path(f'/proc/{pid}/smaps_rollup').read_text().splitlines() if line.startswith(('Rss:','Pss:'))}
def ticks(pid):
    fields=Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].split(); return int(fields[11])+int(fields[12])
def color_count(image,color,box):
    return sum(pixel==color for pixel in image.crop(box).getdata())

def exercise(args,desktop,fixtures,theme):
    env=os.environ.copy(); env.update(DISPLAY=args.display,SLINT_BACKEND='winit-software',WINIT_UNIX_BACKEND='x11',ZAPLAUD_THEME=theme)
    log=open(f'/tmp/zaplaud-rs-shell-{theme}.log','w'); start=time.perf_counter()
    process=subprocess.Popen([args.binary],env=env,stdout=log,stderr=subprocess.STDOUT)
    try:
        wait_for(desktop.mapped,'Janela não mapeada'); mapped_ms=(time.perf_counter()-start)*1000
        desktop.screenshot('login-'+theme); colors=palette(theme)
        def login(port):
            desktop.fill(162,f'http://127.0.0.1:{port}'); desktop.fill(230,'android-test'); desktop.fill(298,'android-test'); desktop.click(550,359)
            desktop.screenshot('http-warning-'+theme); desktop.click(700,442)
            background=colors['surfaceContainerLowest' if theme=='dark' else 'surfaceContainerLow']
            wait_for(lambda:desktop.image().getpixel((1000,700))==background,'Login não abriu lista')
            wait_for(lambda:len(set(desktop.image().crop((12,140,65,600)).getdata()))>30,'Nenhum avatar/linha visível')
        login(fixtures[0].port); time.sleep(.5)
        desktop.click(180,160)
        wait_for(lambda:color_count(desktop.image(),colors['secondaryContainer'],(12,125,348,250))>1000,'Linha não selecionada')
        desktop.screenshot('list-wide-selected-'+theme)
        desktop.key('Escape'); time.sleep(.2)
        assert color_count(desktop.image(),colors['secondaryContainer'],(12,125,348,250))<1000, 'Esc não fechou conversa'
        desktop.key('f',('Control_L',)); time.sleep(.2); desktop.screenshot('search-'+theme)
        desktop.key('Escape'); time.sleep(.2)
        desktop.click(328,36); desktop.click(260,124); time.sleep(.2)
        login(fixtures[1].port); time.sleep(.5); desktop.screenshot('two-servers-'+theme)
        desktop.click(180,205); time.sleep(.2); desktop.resize(600)
        assert desktop.mapped()[1]==600, 'Janela não aceitou layout estreito'
        assert desktop.image().getpixel((360,700))!=colors['outlineVariant'], 'Layout estreito manteve divisor de duas colunas'
        desktop.screenshot('narrow-'+theme); desktop.key('Escape'); time.sleep(.2)
        desktop.screenshot('narrow-list-'+theme); desktop.resize(1100)
        fixtures[1].restart(); time.sleep(4)
        desktop.screenshot('relogin-'+theme)
        result={'theme':theme,'launch_to_mapped_ms':mapped_ms,'idle':memory(process.pid),'window_pixels':list(desktop.mapped()[1:])}
        if args.measure:
            desktop.x.XSetInputFocus(desktop.d,desktop.root,1,0); desktop.x.XWarpPointer(desktop.d,0,desktop.root,0,0,0,0,1250,790); desktop.x.XSync(desktop.d,0)
            before=ticks(process.pid); cpu_start=time.perf_counter(); time.sleep(30); elapsed=time.perf_counter()-cpu_start
            result.update(cpu_sample_seconds=elapsed,idle_cpu_percent_one_core=100*(ticks(process.pid)-before)/os.sysconf('SC_CLK_TCK')/elapsed)
        print(json.dumps(result),flush=True); return result
    finally:
        process.terminate(); process.wait(timeout=10); log.close()

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--binary',required=True); parser.add_argument('--display',default='localhost:85'); parser.add_argument('--port',type=int,default=14785); parser.add_argument('--measure',action='store_true'); parser.add_argument('--theme',choices=['light','dark','both'],default='both'); args=parser.parse_args()
    desktop=Desktop(args.display); fixtures=[]
    try:
        fixtures=[Fixture(args.port),Fixture(args.port+1)]
        results=[exercise(args,desktop,fixtures,theme) for theme in (['light','dark'] if args.theme=='both' else [args.theme])]
        Path('/tmp/zaplaud-rs-shell-measurements.json').write_text(json.dumps(results,indent=2))
    finally:
        for fixture in fixtures: fixture.stop()
if __name__=='__main__': main()
