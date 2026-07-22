# Walkthrough: Adăugarea opțiunii de setare Bitrate

Am implementat posibilitatea de a seta manual bitrate-ul pentru streaming și înregistrare video. Această setare oferă utilizatorilor avansați control direct asupra lățimii de bandă utilizate, independent de parametrul general de "calitate".

## Schimbări Efectuate

### [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- **Persistență**: Am adăugat `PREF_VIDEO_BITRATE` pentru a salva preferința utilizatorului.
- **Interfață**: Dialogul de configurare a destinației stream-ului include acum un câmp pentru Bitrate (în kbps). O valoare de `0` activează modul auto (bazat pe calitate).
- **Encoder H.265 (TCP/SRT)**: Metoda `startH264Encoder` verifică dacă există un bitrate manual setat. Dacă da, îl aplică direct; altfel, folosește algoritmul de calcul bazat pe rezoluție și calitate.
- **RTMP**: În `startRtmpForwardingThread`, am integrat bitrate-ul manual în apelul `prepareVideo` al librăriei de streaming.
- **Control la Distanță**: Am actualizat protocolul de control TCP (folosit de plugin-ul OBS) pentru a accepta parametrul `bitrate=X`. Acest lucru permite modificarea bitrate-ului direct din OBS.

### [InternalCameraHelper.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/InternalCameraHelper.java)
- Am adăugat suport pentru setarea bitrate-ului în `MediaRecorder`. Aceasta asigură că înregistrările video salvate local pe telefon respectă setarea aleasă de utilizator.

### [strings.xml](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/res/values/strings.xml)
- Am adăugat resursele de text necesare pentru noile elemente de interfață.

## Verificare

### Teste Efectuate
- [x] Verificarea persistenței setării după închiderea și redeschiderea aplicației.
- [x] Testarea dialogului de setări pentru a asigura că input-ul este validat corect.
- [x] Verificarea log-urilor encoderului pentru a confirma aplicarea bitrate-ului manual (ex: "H.265 encoder started ... @ 5 Mbps").
- [x] Testarea integrării cu controlul la distanță (OBS) prin trimiterea unui mesaj de control care conține `bitrate=8000`.

> [!TIP]
> Pentru cele mai bune rezultate pe o rețea Wi-Fi standard, un bitrate între 4000 și 8000 kbps (4-8 Mbps) pentru 1080p 30fps este de obicei suficient de bun pentru o calitate ridicată fără a introduce lag excesiv.
