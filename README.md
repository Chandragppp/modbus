# Modbus Tool (`id.controlengineer.modbus`)

Klien (master) **Modbus TCP** dan **Modbus RTU (USB-OTG RS485/RS232)** untuk teknisi lapangan dan engineer otomasi industri.

- **`:protocol`**: Modul Kotlin/JVM murni berisi encoder/decoder PDU Modbus (FC 01, 02, 03, 04, 05, 06, 15, 16), framing MBAP TCP & RTU CRC-16/MODBUS, pemetaan alamat 0-based & Modicon 5/6-digit, dekoder Float32/Int32 4 urutan byte (`ABCD`, `CDAB`, `BADC`, `DCBA`), skala linear analog, dan server/simulator uji.
- **`:app`**: Aplikasi Android Jetpack Compose Material 3 (terang/gelap, Bahasa Inggris & Bahasa Indonesia) dengan dukungan USB Serial (`usb-serial-for-android`), monitor frame hex TX/RX, pemindai slave/baud, pencatat CSV, dan grafik tren.

> **Penafian**: Aplikasi independen, tidak berafiliasi dengan Modbus Organization.
