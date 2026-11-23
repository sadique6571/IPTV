import subprocess, time, json, re
import xml.etree.ElementTree as ET
import uiautomator2 as u2
pkg="com.sadique.iptv"
def adb(*args,data=None): subprocess.run(["adb",*args],input=data,check=True)
folder="/sdcard/Android/data/"+pkg+"/files/downloads"
adb("shell","mkdir","-p",folder)
adb("push","/tmp/audio-test.mp4",folder+"/audio-test.mp4")
prefs=ET.Element("map")
ET.SubElement(prefs,"string",name="saved").text=json.dumps([dict(key="test",id="test",title="Audio fixture",file="audio-test.mp4",done=True)])
adb("shell","run-as",pkg,"mkdir","-p","shared_prefs")
adb("exec-in","run-as",pkg,"sh","-c","'cat > shared_prefs/downloads.xml'",data=ET.tostring(prefs))
d=u2.connect()
d.screen_on()
d.shell("wm dismiss-keyguard")
d.app_start(pkg,activity=".MainActivity",stop=True)
assert d(text="Movies").wait(timeout=25)
d(text="Movies").click()
assert d(text="TV Shows").wait(timeout=10)
for i in range(4):
    if d(text="Downloads").exists: break
    b=d(text="TV Shows").info["bounds"]
    d.swipe(.92,(b["top"]+b["bottom"])/2/d.window_size()[1],.1,(b["top"]+b["bottom"])/2/d.window_size()[1],.3)
d(text="Downloads").click()
d(text="Audio fixture").click()
a=d(description="Audio language")
def show():
    if not a.exists: d.click(.5,.5)
    assert a.wait(timeout=3)
time.sleep(8)
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
