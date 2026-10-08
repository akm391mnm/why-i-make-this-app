# AutoClicker
แอปจับภาพหน้าจอ -> หาปุ่มด้วย OpenCV template matching -> แตะผ่าน Accessibility Service
ใส่ภาพปุ่มที่ app/src/main/assets/*.png แล้ว push ขึ้น GitHub จะ build APK ให้อัตโนมัติ
(ดาวน์โหลดได้ที่ Releases > latest หรือ Actions > autoclicker-apk)

## อัปเดตในแอป
ปุ่ม "ตรวจสอบอัปเดต" เช็ค release tag `latest` ของ repo `akm391mnm/why-i-make-this-app`
(repo ต้องเป็น public) versionCode = เลขรอบ build ของ GitHub Actions

## Logs
สร้างไฟล์ใหม่ทุกครั้งที่กด ▶ เริ่ม เก็บที่ `files/logs/` ส่งออกเป็น .txt ได้จากปุ่ม "Logs" ในหน้าหลัก
