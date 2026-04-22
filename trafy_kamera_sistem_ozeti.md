# Trafy Araç İçi Kamera Sistemi — Teknik Özet

> Bu belge, bir konuşmanın devamı niteliğinde başka bir LLM oturumuna aktarılmak üzere hazırlanmıştır.
> Tüm teknik detaylar, kararlar ve açık sorular buradadır.

---

## 1. Proje Bağlamı

**Proje adı:** Trafy  
**Mevcut altyapı:** VDS sunucu (oweb), IP: `109.104.120.36`  
**Sunucu işletim sistemi:** Ubuntu 24.10  
**Domain:** `https://109-104-120-36.nip.io/`

Sunucuda çalışan portlar (Docker container'lar):
- `:80`, `:81`, `:443` — web servisleri
- `:8080`, `:8081` — uygulama servisleri
- `:3000` — Grafana / izleme
- `:8443` — HTTPS alternatif
- `:9090` — Prometheus
- `:22` — SSH

Sunucuda IP Forwarding **açık** (`net.ipv4.ip_forward = 1`).

---

## 2. Kamera Donanımı

**Model:** WMC-E21 with GPS  
**Chip:** Allwinner V853 (Linux tabanlı, AI destekli)  
**Çözünürlük:** 2K (ön) + 1080P (arka), çift kamera  
**Lens:** 140° + 110°  
**Sensör:** OV2053  
**GPS:** Standart dahili  
**Depolama:** Maks. 256GB SD kart  
**4G Bantları:** B1, B3, B5, B8, B38, B39, B40, B41 (Türkiye'de Türk Telekom uyumlu)  
**Bağlantı tipi:** 4G LTE üzerinden internet; veri SIM kart ile çalışır  
**Mobil uygulama:** **CloudSpirit** (üretici tarafından verilen Çin yapımı uygulama)  
**Tedarik:** Çin'den ithal

### Kameranın Mevcut Çalışma Mantığı (CloudSpirit P2P):
```
Kamera (4G SIM) → CloudSpirit Çin Sunucuları (P2P/TUTK) → CloudSpirit Mobil App
```
Kamera, QR kodu ile CloudSpirit uygulamasına eklendi ancak **canlı görüntü alınamıyor**.

---

## 3. SIM / APN Kurulumu

**Operatör:** Türk Telekom  
**Hat tipi:** **M2M (Machine to Machine)** kurumsal hat  
**Mevcut APN talebi:** `NAT44 APN tanımı` — daha önce Türk Telekom'a e-posta ile iletildi

### NAT44 Nedir?
- SIM karta özel (private) bir IPv4 adresi atanır (örn. `10.x.x.x`)
- Türk Telekom'un NAT gateway'i bu IP'yi kendi public IP havuzundan internete çıkarır
- Kamera **outbound** bağlantı kurabilir (internete çıkabilir)
- Sunucudan kameraya **inbound** bağlantı **KURULAMAZ** (kamera NAT arkasında)

### Private APN Durumu:
Kullanıcı Türk Telekom'a verdiği IP adresi: `109.104.120.36`  
Ancak sunucuda **herhangi bir tünel arayüzü (GRE/L2TP/IPSec/VPN) kurulu değil.**  
Sunucuda sadece `eth0` (public), `docker0` ve Docker bridge arayüzleri mevcut.  
→ **Private APN tüneli henüz aktif değil / doğru kurulmamış.**

---

## 4. Neden Görüntü Alınamıyor? (Mevcut Sorun Analizi)

İki olası neden:

### a) Private APN yanlış yapılandırması
M2M hattı private APN ile yapılandırılmışsa tüm trafik sunucuya tünellenmek istiyor olabilir, ancak sunucuda tünel endpoint olmadığı için kamera internete çıkamıyor → CloudSpirit sunucularına ulaşamıyor → görüntü yok.

### b) CloudSpirit P2P bağlantısı kurulamıyor
Kamera, CloudSpirit'in Çin sunucularına P2P tüneli açması gerekiyor. NAT44 arkasındaki bir cihaz için bu P2P handshake (TUTK protokolü) bazen başarısız olur.

---

## 5. Kullanıcının Gerçek Hedefi

> **CloudSpirit'i tamamen bypass edip kendi uygulamasından canlı izleme yapmak.**

İstenen mimari:
```
📷 Kamera (4G M2M SIM)
    ↓ stream gönderir
🖥️ Kullanıcının kendi sunucusu (109.104.120.36)
    ↓ HLS veya WebRTC'ye çevirir
📱 Kullanıcının kendi mobil uygulaması
```

Bu **teknik olarak mümkündür** ancak aşılması gereken engeller var.

---

## 6. Teknik Engeller ve Çözüm Yolları

### Engel 1: Kameranın NAT arkasında olması
**Sorun:** NAT44 ile sunucudan kameraya doğrudan bağlantı kurulamaz.

**Çözümler:**
| Yöntem | Açıklama | Zorluk |
|---|---|---|
| **Static IP APN** | Her SIM'e sabit public IP | ⭐ Kolay (TT'den talep) |
| **Reverse Tunnel** | Kamera sunucuya tünel açar | ⭐⭐ Orta (firmware gerekir) |
| **TURN/WebRTC relay** | Sunucu aracı olur | ⭐⭐ Orta |

### Engel 2: Kameranın RTSP destekleyip desteklemediği bilinmiyor
Allwinner V853 çipli kameralar genellikle RTSP'yi destekler ama:
- RTSP yalnızca yerel WiFi ağında erişilebilir olabilir
- Dynamic token gerektirebilir
- Üretici tarafından bloke edilmiş olabilir

**Test yöntemi:** Kameranın WiFi hotspot'una telefonla bağlan, şu URL'leri VLC ile dene:
```
rtsp://192.168.1.1:554/live
rtsp://192.168.169.1:554/live
rtsp://192.168.1.1:8554/
rtsp://admin:admin@192.168.1.1:554/video
```

### Engel 3: CloudSpirit proprietary protokol
CloudSpirit büyük ihtimalle **TUTK (ThroughTek)** P2P SDK kullanıyor. Bu protokol:
- Şifrelidir
- Reverse engineering zordur
- Bypass için firmware değişikliği gerekebilir

---

## 7. Önerilen Eylem Planı (Öncelik Sırası)

### Adım 1 — WiFi RTSP Testi (Hemen Yapılabilir)
Kameranın WiFi hotspot'una bağlan.  
VLC ile RTSP URL'lerini dene.  
→ Eğer RTSP çalışıyorsa (6. bölümdeki URL'ler), sistem kurulabilir.

### Adım 2 — Türk Telekom'a Static IP APN Talebi
Mevcut NAT44 talebine ek olarak:
> "Her M2M SIM'e statik/sabit public IP atanması mümkün müdür? Kameraların IP adreslerine sunucumdan doğrudan erişmem gerekiyor."

Static IP alındığında:
- Sunucudan `ssh` veya `ffplay` ile kameraya doğrudan erişilebilir
- RTSP stream `ffmpeg` ile çekilebilir

### Adım 3 — Sunucuya MediaMTX Kurulumu
[MediaMTX](https://github.com/bluenviron/mediamtx) — hafif, tüm protokolleri destekleyen media server:
```bash
# Docker ile kurulum
docker run -d --name mediamtx \
  -p 8554:8554 \   # RTSP
  -p 8888:8888 \   # HLS
  -p 8889:8889/udp \ # WebRTC
  bluenviron/mediamtx
```

Kamera RTSP push yapar, MediaMTX HLS'e çevirir, mobil uygulama HLS izler.

### Adım 4 — Mobil Uygulama Geliştirme
React Native veya Flutter ile:
- Sunucudan HLS stream okuma (VLC player component)
- GPS konum görüntüleme
- Çoklu kamera desteği

---

## 8. Türk Telekom M2M İletişim Taslağı

Hazırlanan e-posta taslağı (IMEI kilidi dahil değil — test aşaması için birden fazla kamerada aynı SIM kullanılacak):

**Konu:** M2M Hattı – Private IP ve APN Tünel Protokolü Bilgisi Talebi

> Sayın Türk Telekom M2M / IoT Destek Ekibi,
> 
> Bünyemde kullandığım M2M hattı ile ilgili aşağıdaki konularda teknik destek talep etmekteyim.
> 
> **Hat Bilgisi:** [Hat No / ICCID]
> 
> **1. Hatta Atanan Private IP Adresi**
> M2M hattımıza atanan private IP adresini öğrenmek istiyorum.
> 
> **2. Private APN – Sunucu Tünel Yapılandırması**
> Private APN trafiğini kendi bulut sunucuma yönlendirmek istiyorum:
> - Kullanılan tünel protokolü (GRE / L2TP / IPSec?)
> - Sunucu tarafında açılması gereken portlar
> - Türk Telekom tünel uç noktası (endpoint) bilgileri
> 
> Bulut sunucu IP: **109.104.120.36**
> 
> Saygılarımla, [İsim / Şirket]

---

## 9. Açık Sorular / Bilinmeyenler

| Soru | Önem |
|---|---|
| Kamera WiFi hotspot açıyor mu? | 🔴 Kritik |
| RTSP destekli mi? URL nedir? | 🔴 Kritik |
| Türk Telekom NAT44 talebi onaylandı mı? | 🔴 Kritik |
| Static Public IP mümkün mü? | 🟡 Önemli |
| APN tünel tipi nedir (GRE/L2TP)? | 🟡 Önemli |
| Kameranın 4G üzerinden aldığı private IP nedir? | 🟡 Önemli |
| CloudSpirit'in RTSP'ye izin verip vermediği | 🟠 Araştırılacak |
| Kameranın web arayüzü var mı? | 🟠 Araştırılacak |

---

## 10. Genel Değerlendirme

Proje **teknik olarak uygulanabilir**. Allwinner V853 güçlü bir çip ve RTSP büyük ihtimalle destekleniyor. Ana bloker noktalar:

1. **NAT44 / Static IP sorunu** — TT'den statik IP alınması çok işi kolaylaştırır
2. **RTSP erişim doğrulaması** — WiFi üzerinden test ile anında netleşir
3. **Sunucu tünel yapısı** — TT'nin APN tünel tipi öğrenilmeli

Eğer RTSP çalışıyorsa ve static IP alınırsa:
**1-2 hafta içinde** kendi mobil uygulamasından canlı izleme sistemi kurulabilir.
