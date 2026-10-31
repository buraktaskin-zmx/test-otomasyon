# Tasarım sistemi — Açık mavi tema

Senaryum'un (`/ui`) görsel dili. Frontend'de renk, yazı, köşe,
gölge ya da bileşen görünümüne dokunan her değişiklik bu dosyaya uyar.

Kaynak: Claude Design'da üretilen "Açık mavi tema — renk jetonları" kartı.
Buradaki hex değerleri o karttan **birebir** kopyalandı. Kartta olmayan bir
değer burada **uydurulmadı**; eksik olanlar en alttaki *Açık sorular* bölümünde.

> **Durum (2026-10-31): uygulandı — yalnızca React arayüzünde.** Değerler
> `src/theme.css` sonundaki "AÇIK MAVİ TEMA" bölümünde. Orası `app.css`'ten
> sonra yüklenir ve eski değişken adlarını yeni değerlere bağlar
> (`--brand: var(--primary)`, `--text: var(--ink)` …), bu yüzden mevcut
> kuralların hiçbiri değişmeden yeni renge geçti. `app.css` dokunulmadı:
> eski `/index.html` Yapı Kredi lacivertinde (`#004587`) kalır, koyu tema da
> olduğu gibi durur. Yeni kod ister eski ister yeni adı kullanabilir; hex
> değeri hiçbir bileşene doğrudan yazılmaz.

---

## 1. Renk jetonları

### Zeminler

| Değişken | Hex | Nerede |
|---|---|---|
| `--bg` | `#F4F8FD` | sayfa zemini |
| `--surface` | `#FFFFFF` | kart, ray (sol menü) |
| `--surface-alt` | `#FAFCFE` | ağaç paneli |
| `--surface-head` | `#F7FAFD` | tablo başlığı |

### Mavi aile

| Değişken | Hex | Nerede |
|---|---|---|
| `--primary` | `#1570EF` | ana buton, aktif öğe şeridi |
| `--primary-ink` | `#0B3F8F` | aktif metin (seçili menü/ağaç öğesinin yazısı) |
| `--primary-tint` | `#E7F0FD` | aktif zemin, üst bar |
| `--primary-line` | `#CDE0F8` | mavi kenarlık |
| `--link` | `#1057C4` | bağlantı, rozet metni |
| `--primary-soft` | `#2E90FA` | alternatif aksan (metin için DEĞİL, bkz. §2) |

**Doygun maviyi (`--primary`) yalnızca iki yerde kullan:** birincil buton ve
aktif öğenin soldaki ince şeridi. (Kartta logo da vardı; uygulama işaretsiz,
yalnızca "Senaryum" yazısıyla çıkıyor.) Geri kalan her "mavi" ya `--primary-tint`
zemin ya `--primary-ink` / `--link` metindir.

### Metin

| Değişken | Hex | Nerede |
|---|---|---|
| `--ink-title` | `#0B2F5E` | H1 ve marka adı |
| `--ink` | `#15263C` | ana metin |
| `--muted` | `#3C5876` | açıklama |
| `--soft` | `#5B718F` | etiket, sütun başlığı |
| `--mono` ⚠ | `#64748B` | dosya yolu |
| `--empty` | `#8093AD` | boş değer tiresi (—) |

Metin hiçbir yerde saf siyah (`#000`) değildir; en koyusu `--ink-title`.

> ⚠ **Ad çakışması:** `--mono` adı projede ZATEN var ve bir **yazı tipi
> ailesi** tutuyor (`app.css` ve `theme.css`: `--mono: "Cascadia Mono", …`).
> Renk jetonunu bu adla koymak editörün fontunu bozar. Koda taşırken renk
> `--ink-mono` adını alır; bu belgede iki ad aynı rengi anlatır.

### Çizgiler

| Değişken | Hex | Nerede |
|---|---|---|
| `--border` | `#E2EAF4` | kart ve panel kenarı |
| `--border-soft` | `#EDF2F8` | iç ayırıcı |
| `--row-line` | `#F1F5FA` | tablo satır çizgisi |
| `--chip-bg` | `#F2F6FC` | nötr sayaç (ağaçtaki "50" gibi) |

### Durumlar — temadan BAĞIMSIZ

| Durum | Renk | Zemin / metin | Projedeki karşılığı |
|---|---|---|---|
| engelli | `#D92D20` | zemin `#FEF3F2` | `Readiness.BLOCKED`, `.is-blocked` |
| eksik | `#B54708` | zemin `#FFFAEB` | `Readiness.MISSING`, `.is-missing` |
| seed | `#667085` | zemin `#F2F4F7` | `Readiness.SEED`, `.is-seed` |
| başarılı | `#099250` | metin `#067647` | `READY` / başarılı koşu, `.is-ready` |

Durum renkleri **asla maviye çekilmez**. Gerekçe: "hata" ile "aktif menü"
aynı renge düşerse ekranda hangisinin uyarı olduğu okunamaz. Tema değişse de
bu dört renk aynı kalır.

`başarılı` için iki değer var. `#099250` yalnızca **nokta, ikon, şerit** gibi
metin olmayan işaretlerde kullanılır; beyaz üzerinde 4.01:1 kaldığı için
**yazıda** `#067647` kullanılır.

---

## 2. Kontrast — ölçülmüş değerler

WCAG AA: küçük metin ≥ **4.5:1**, büyük metin (≥18.66px kalın / 24px) ve
metin olmayan işaretler ≥ **3:1**. Oranlar hesaplandı, tahmin değil.

| Ön plan / zemin | Oran | Sonuç |
|---|---|---|
| `#FFFFFF` / `--primary` | 4.57 | ✅ birincil buton yazısı |
| `--primary-ink` / `--primary-tint` | 8.62 | ✅ aktif menü yazısı |
| `--link` / `#FFFFFF` | 6.60 | ✅ |
| `--link` / `--primary-tint` | 5.74 | ✅ |
| `--primary` / `--primary-tint` | 3.98 | ❌ küçük metin — tint üstünde `--primary-ink` ya da `--link` yaz |
| `--primary-soft` / `#FFFFFF` | 3.24 | ❌ metin olarak kullanma; yalnız aksan |
| `--ink-title` / `#FFFFFF` | 13.27 | ✅ |
| `--ink` / `--bg` | 14.33 | ✅ |
| `--muted` / `--bg` | 6.91 | ✅ |
| `--soft` / `--surface-head` | 4.77 | ✅ sütun başlığı |
| `--soft` / `--chip-bg` | 4.61 | ✅ |
| `--mono` / `--surface-alt` | 4.63 | ✅ dosya yolu |
| `--empty` / `#FFFFFF` | 3.14 | ❌ küçük metin — yalnızca anlamı başka yerden de okunan "—" için; bilgi taşıyan yazıda kullanma |
| engelli `#D92D20` / `#FEF3F2` | 4.44 | ❌ sınırda — rozet metni kalın (600+) ve ≥ 12px olmalı ya da beyaz zeminde kullan (4.83) |
| eksik `#B54708` / `#FFFAEB` | 5.20 | ✅ |
| seed `#667085` / `#F2F4F7` | 4.51 | ✅ sınırda — zemini koyulaştırma |
| başarılı `#067647` / `#FFFFFF` | 5.69 | ✅ |

Yeni bir renk çifti eklersen oranını hesapla ve bu tabloya yaz.

---

## 3. Biçim kuralları

| | Değer |
|---|---|
| Köşe — kart | **16px** |
| Köşe — buton, satır | **10–11px** |
| Köşe — rozet | **7px** |
| Köşe — pill | **999px** |
| Gölge | tek katman: `0 1px 2px rgba(16, 44, 86, .05)` — ağır gölge yok |
| Başlık (H1) | Inter **30 / 700** |
| Satır başlığı | **14 / 600** |
| Gövde | **13 / 400** |
| Dosya yolu, kod benzeri değer | JetBrains Mono **11.5** |

Kaynak kartın "BİÇİM KURALLARI" bölümünün ekran görüntüsünde okunabilen kısmı
bu üç satır (köşe, gölge, tipografi). Devamı kadrajda kesik; okunmayan kural
buraya eklenmedi.

**Yazı tipleri hakkında — önemli kısıt:** Inter ve JetBrains Mono VDI'da
kurulu değil ve bu UI **hiçbir dış kaynağa istek atmaz**. Kurumsal SSL
denetimi dışarı giden tarayıcı isteklerini engelliyor, ayrıca `theme.css`
bilerek `@font-face` ya da CDN bağlantısı içermiyor. Bugün geçerli yığın
`theme.css` `--sans` / `--mono` (Segoe UI Variable / Cascadia Mono) olarak
duruyor. Inter'e geçmek ancak fontu **paketin içine gömerek** olur (örn. npm
ile kurulup Vite'ın bundle'ına giren dosyalar, jar'dan sunulur). Bu ayrı bir
karar; sorulmadan yapılmaz. O zamana kadar ölçüler (30/700, 14/600, 13/400)
mevcut yığına uygulanır.

---

## 4. Bileşen kuralları

- **Aktif menü öğesi:** dolu renkli blok **değil**. Zemin `--primary-tint`,
  yazı `--primary-ink` (600), solda ince `--primary` şerit (3px — ağaçtaki `.tree-row.is-active`
  bugün bu genişliği kullanıyor).
  Sol ray, ağaç ve sekmeler aynı kalıbı kullanır.
- **Birincil buton:** `--primary` zemin, beyaz yazı, 10–11px köşe. Ekranda
  aynı anda bir tane (Kaydet, Çalıştır).
- **İkincil buton:** `--surface` zemin, `--border` kenar, `--ink` yazı;
  hover'da `--primary-tint` zemin + `--primary-line` kenar (bugünkü
  `.btn-ghost`).
- **Kart / panel:** `--surface`, `--border` kenar, 16px köşe, §3'teki tek
  katman gölge. Kart içinde ikinci bir çerçeveli kart yok.
- **Tablo:** başlık satırı `--surface-head`, başlık yazısı `--soft`
  büyük harf; satır çizgisi `--row-line`; hover `--surface-alt`. Tablo kendi
  kutusunda yatay kayar (`.registry-scroll`), sayfa kaymaz.
- **Sayaç rozeti** (ağaçtaki senaryo sayısı, başlık satırındaki sayı):
  `--chip-bg` zemin, `--link` ya da `--soft` yazı, 7px köşe.
- **Dosya yolu** (`alici-listesi/28-senaryo.feature`): mono font,
  `--mono`/`--ink-mono` rengi, 11.5px.
- **Boş değer:** "—" ve `--empty`. Bilgi taşıyorsa (`oturum kaydı bekliyor`)
  `--soft` ya da `--muted` kullan.
- **Durum işaretleri:** §1 tablosundaki dört renk; renk tek başına anlam
  taşımaz, yanında her zaman sözcük vardır (`engelli`, `eksik`, `seed`,
  `Başarılı`).

## 5. İstenmeyenler

- Gradyan dolgu, cam (blur) efekti, 3D, ağır gölge
- Sol kenarı renkli kartlar (şerit yalnız aktif MENÜ öğesinde)
- Çerçeve içinde çerçeve
- Emoji ikon — ikonlar inline, stroke'lu SVG
- Tıklanabilir `div`/`span` — tıklanan her şey `<button>` ya da `<a href>`
- Saf siyah metin, durum renklerini maviye boyamak

## 6. Erişilebilirlik ve duyarlılık

- Küçük metin ≥ 4.5:1 (§2). Odak halkası görünür kalır
  (`theme.css` `:focus-visible` kuralları).
- Tablolar gerçek `<table>` + `<th>`; açılır/kapanır satırlar `aria-expanded`
  taşır; menüler Esc ile kapanır, ok tuşlarıyla gezilir.
- Dar ekranda (telefon genişliği) menü alta iner; tablo yatay kayar,
  sayfa kaymaz.

---

## 7. Eski değişkenden yeni jetona eşleme

Eski ad korunur, değeri yeni jetona bağlanır (`src/theme.css`); böylece bu
değişkenleri kullanan sınıfların hiçbirine dokunmak gerekmez. Yeni ad yalnızca
karşılığı olmayan jetonlar için eklendi.

| Bugünkü değişken (app.css) | Bugünkü değer | Hedef jeton | Hedef değer |
|---|---|---|---|
| `--brand` | `#004587` | `--primary` | `#1570EF` |
| `--brand-tint` | `#eaf1f8` | `--primary-tint` | `#E7F0FD` |
| `--brand-line` | `#cfdeed` | `--primary-line` | `#CDE0F8` |
| `--on-brand` | `#ffffff` | — | `#FFFFFF` |
| `--bg` | `#f4f6f8` | `--bg` | `#F4F8FD` |
| `--surface` | `#ffffff` | `--surface` | `#FFFFFF` |
| `--surface-2` | `#fafbfc` | `--surface-alt` | `#FAFCFE` |
| `--border` | `#dee3e9` | `--border` | `#E2EAF4` |
| `--border-soft` | `#eaeef2` | `--border-soft` | `#EDF2F8` |
| `--text` | `#17212c` | `--ink` | `#15263C` |
| `--text-muted` | `#5a6773` | `--muted` | `#3C5876` |
| `--text-faint` | `#8b96a2` | `--soft` | `#5B718F` — `--empty` değil: bu değişken arayüzde gerçek sözcükler için kullanılıyor ve `--empty` 3.14:1 ile AA'yı geçmiyor |
| `--fail` / `--fail-bg` | `#c0272d` / `#fdecec` | engelli | `#D92D20` / `#FEF3F2` |
| `--warn` / `--warn-bg` | `#9a6200` / `#fdf3e2` | eksik | `#B54708` / `#FFFAEB` |
| `--skip` / `--skip-bg` | `#8b96a2` / `#f1f3f5` | seed | `#667085` / `#F2F4F7` |
| `--ok` | `#0b7a3e` | başarılı (metin) | `#067647` |
| — | — | `--ok-mark` | `#099250` (nokta, ikon, şerit) |
| `--brand-hover` | `#003a72` | `--link` | `#1057C4` |
| `--brand-active` | `#002f5c` | `--primary-ink` | `#0B3F8F` |
| `--shadow` | iki katman | — | `0 1px 2px rgba(16,44,86,.05)` |
| `--radius` / `--radius-sm` | `8px` / `6px` | kart / buton | `16px` / `10px` |

Yeni eklenenler (karşılığı yoktu): `--primary`, `--primary-ink`, `--link`,
`--primary-soft`, `--primary-tint`, `--primary-line`, `--ink`, `--muted`,
`--ink-title`, `--soft`, `--ink-mono`, `--empty`, `--surface-alt`,
`--surface-head`, `--row-line`, `--chip-bg`, `--ok-mark`,
`--radius-badge: 7px`, `--radius-pill: 999px`. Koyu temada bunlar koyu
paletin kendi değerlerine bağlanır; yeni koyu renk uydurulmadı.

`.sc-ready.is-seed` artık `--skip` (gri). Eskiden `--run` (mavi) kullanıyordu
ve yeni temada aktif menüyle aynı maviye düşerdi.

Bileşen tarafında yapılanlar (aynı bölümde): üst bar dolu mavi bant yerine
`--primary-tint`; marka yalnızca "Senaryum" yazısı (`--ink-title`, 700), işaret yok; sekmeler, sunucu
hapı ve tema düğmesi beyaz zeminli; durum noktaları (`.dot-*`) beyaz üstünde
görünür renklere; aktif ray/ağaç öğesi `--primary-ink` yazı + 3px şerit;
ağaç sayacı `--chip-bg` rozet; tablo başlığı `--surface-head`/`--soft`,
satır çizgisi `--row-line`; senaryo adı `--ink` 14/600, dosya yolu
`--ink-mono` 11.5px; kural çipi 7px köşe.

---

## Açık sorular — palette yok, uydurulmadı

Geçişte verilen geçici kararlar — kart netleşirse değişir:

1. **Hover / active:** paletin kendi koyu maviler kullanıldı
   (`--link`, `--primary-ink`); yeni ton uydurulmadı.
2. **Koyu tema:** eski koyu palet olduğu gibi kaldı.
3. **`--run` / `--run-bg` (koşuyor)** ve **`--ok-bg`**: kartta yok,
   `app.css` değerleri kaldı.
4. **Yazı tipi:** `--sans` Inter'i, `--mono` JetBrains Mono'yu İLK sıraya
   aldı ama hiçbiri indirilmiyor; kurulu olmayan makinede (VDI dahil) Segoe UI
   Variable / Cascadia Mono görünür. Gömme ayrı karar (§3).
5. **H1 30/700:** başlık yalnızca 700 kalınlık ve `--ink-title` aldı, boyut
   (16px) korunuyor; 30px dar panellerde başlığı taşırır, onaylanınca
   uygulanır.
6. Kaynak kartın "BİÇİM KURALLARI" bölümünün kesik kalan devamı hâlâ okunmadı.
