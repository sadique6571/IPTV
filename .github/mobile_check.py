import re
import time
import uiautomator2 as u2

d = u2.connect()
d.app_start("com.sadique.iptv", stop=True)
def report(step):
    print("PHONE_TEST", step, flush=True)
def summary():
    import xml.etree.ElementTree as ET
    xml = ET.fromstring(d.dump_hierarchy())
    words = [n.get("text","") for n in xml.iter("node") if n.get("package") == "com.sadique.iptv"]
    return [re.sub(r'https?://\S+', '[URL]', w)[:100] for w in words if w and "token" not in w.lower()][-65:]
def tap(obj, name, scrolling=False):
    if not obj.wait(timeout=12) and scrolling:
        for _ in range(5):
            d.swipe_ext("up", scale=.5)
            if obj.exists: break
    if not obj.exists:
        report(str((name, summary())))
        raise RuntimeError(name + " missing")
    obj.click()
    report(name)

report("Android "+str(d.device_info["version"]))
tap(d(textMatches="(?i).*Movies.*", packageName="com.sadique.iptv"),"Movies on phone",True)
tap(d(className="android.widget.EditText",textContains="Movie"),"Movie search")
field=d(className="android.widget.EditText", focused=True)
field.set_text("The Body")
field.click()
time.sleep(2)
report("keyboard "+str(d.shell("dumpsys input_method | grep mInputShown | head -1").output.strip())[:150])
keyboard=d(descriptionMatches="(?i)^search$",packageNameMatches=".*(inputmethod|latin).*")
if keyboard.exists:
    keyboard.click()
else:
    d.click(.94,.91)
time.sleep(14)
if d(text="Trending · New Release").exists:
    field=d(className="android.widget.EditText",text="The Body")
    field.click()
    d.set_input_ime(True)
    time.sleep(3)
    field.click()
    d.send_action(3)
    time.sleep(20)
report("search results "+str(summary()))
tap(d(textMatches=r"(?i)^The Body\s*\[Hindi\].*$"),"Exact Hindi series",True)
time.sleep(6)
tap(d(textMatches=r"(?i)^(Episode 1|E1\s*·.*)$"),"Season 1 episode 1",True)
time.sleep(10)
d.swipe_ext("up",scale=.62)
time.sleep(4)
report("episode view "+str(summary()))
tap(d(text="Play",className="android.widget.Button",enabled=True),"Play",True)
time.sleep(6)
report("player screen "+str(summary()))
samples=[]
for _ in range(4):
    d.click(.5,.5)
    time.sleep(.4)
    samples.append([s for s in summary() if re.match(r"^\d+:\d\d(:\d\d)?$",s)])
    time.sleep(3)
report("last player screen "+str(summary()))
report("visible elapsed playback times "+str(samples))
if len({t for row in samples for t in row if t not in ("0:00","00:00")})<2:
    raise RuntimeError("Playback advancement unverified")
