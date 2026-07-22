# Plan: Integrare Bitrate în OBS Plugin și Sincronizare Bidirecțională

Acest plan detaliază pașii necesari pentru a finaliza integrarea bitrate-ului, asigurându-ne că plugin-ul OBS poate controla bitrate-ul și că starea acestuia este sincronizată corect între Android și OBS.

## User Review Required

> [!IMPORTANT]
> Această modificare necesită recompilarea plugin-ului OBS pentru a vedea noile opțiuni în interfața OBS.

## Proposed Changes

### [Component Name] :app (Android)

#### [MODIFY] [MainActivity.java](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/app/src/main/java/com/herohan/uvcapp/activity/MainActivity.java)
- Actualizarea `buildTcpControlStatePayload` pentru a include parametrul `bitrate=%d` în mesajul trimis către OBS. Acest lucru asigură că, dacă bitrate-ul este modificat pe telefon, OBS va afla de această schimbare.

### [Component Name] obs-plugin

#### [MODIFY] [uvc_custom_network.h](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/obs-plugin/uvc_custom_network.h)
- Adăugarea câmpurilor `int bitrate` și `int pending_bitrate` în structura `uvc_custom_network`.

#### [MODIFY] [uvc_custom_network.cpp](file:///C:/Users/Emil/Documents/Coding/ViewAndStream/UVCAndroid/obs-plugin/uvc_custom_network.cpp)
- **Defaults**: Setarea valorii implicite pentru bitrate la `0` (modul auto).
- **Properties**: Adăugarea unui câmp numeric (integer) pentru Bitrate în panoul de proprietăți al sursei în OBS.
- **Update Logic**: Detectarea schimbărilor de bitrate în OBS și trimiterea comenzii `CONTROL` corespunzătoare către telefon.
- **Control Sending**: Includerea parametrul `bitrate=%d` în funcția `uvc_custom_network_send_control`.
- **State Receiving**: Actualizarea `uvc_custom_network_apply_remote_control_state` pentru a parsa bitrate-ul primit de la telefon și a actualiza interfața OBS.
- **Persistence**: Salvarea setării de bitrate în setările sursei OBS.

## Verification Plan

### Manual Verification
1.  **Sincronizare OBS -> Telefon**: Modificați bitrate-ul în OBS și verificați (prin Logcat pe Android) dacă telefonul primește noua valoare și o aplică encoderului.
2.  **Sincronizare Telefon -> OBS**: Modificați bitrate-ul pe telefon (în dialogul "Set Stream Destination") și verificați dacă valoarea se actualizează automat în interfața OBS (după o scurtă întârziere pentru polling).
3.  **Persistență**: Închideți și redeschideți OBS pentru a verifica dacă valoarea bitrate-ului este salvată corect pentru sursa respectivă.
