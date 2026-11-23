import subprocess, time, json, re
import xml.etree.ElementTree as ET
import uiautomator2 as u2
pkg="com.sadique.iptv"
def adb(*args,data=None): subprocess.run(["adb",*args],input=data,check=True)
folder="/sdcard/Android/data/"+pkg+"/files/downloads"
adb("shell","mkdir","-p",folder)
adb("push","/tmp/audio-test.mp4",folder+"/audio-test.mp4")
d=u2.connect()
d.screen_on()
d.shell("wm dismiss-keyguard")
d.app_start(pkg,activity=".MainActivity",stop=True)
a=d(description="Audio language")
def show():
    if not a.exists: d.click(.5,.5)
    assert a.wait(timeout=5), str([(n.get("class"),n.get("text"),n.get("content-desc")) for n in ET.fromstring(d.dump_hierarchy()).iter("node") if n.get("package")==pkg])
time.sleep(12)
show()
def elapsed():
    text=d(textMatches=r"^[0-9]+:[0-9]{2}$").get_text()
    m,s=map(int,text.split(":"))
    return m*60+s
before=elapsed()
a.click()
assert d(textMatches="(?i).*urdu.*").exists
d(textMatches="(?i).*urdu.*").click()
time.sleep(2)
show()
after=elapsed()
a.click()
assert d(textMatches="(?i).*urdu.*",checked=True).exists
assert before>2 and before<=after<=before+12, (before,after)
print("PHONE_TEST Urdu track selected; position before/after",before,after)
