# Frontend — React arayüzü (`/ui`)

React 19 + TypeScript, Vite ile derlenir. Proje genelinin kuralları kökteki
`CLAUDE.md`'de (özellikle *UI: React, not the legacy vanilla files*); bu dosya
yalnızca bu klasörde kod yazarken gerekenleri toplar.

## Önce oku

**[`DESIGN.md`](DESIGN.md) — renk jetonları, kontrast oranları, köşe/gölge/
tipografi ve bileşen kuralları.** Renk, yazı ya da görünüm içeren her
değişiklikten önce okunur. Kısaca:

- Hex değeri bileşene ya da yeni CSS kuralına **yazılmaz**; her renk bir
  CSS değişkeninden gelir. Açık mavi tema `src/theme.css` sonundaki "AÇIK
  MAVİ TEMA" bölümünde tanımlı; eski adlar (`--brand`, `--text`,
  `--text-muted`, `--border`…) yeni değerlere bağlı, yeni adlar
  (`--primary-ink`, `--link`, `--soft`, `--chip-bg`…) de kullanılabilir.
  Eşleme: `DESIGN.md` §7. Renk değişikliği o bölümde yapılır, `app.css`'te
  değil (o dosya eski sayfayla paylaşılıyor).
- Doygun mavi yalnızca birincil buton ve aktif öğe şeridinde. Aktif
  öğe = tonlu zemin + solda ince şerit, dolu blok değil.
- Durum renkleri (engelli / eksik / seed / başarılı) temadan bağımsızdır,
  maviye çekilmez; yanlarında her zaman sözcük vardır.
- Küçük metin ≥ 4.5:1. `DESIGN.md` §2'de ❌ işaretli çiftleri metinde
  kullanma (ör. tint üstünde `--primary`, `--primary-soft` yazı, `--empty`
  bilgi taşıyan yazı).
- `--mono` bir **font** değişkenidir; paletteki "dosya yolu" rengi koda
  `--ink-mono` adıyla girer.

## Dosyalar

```
src/
├── workspace/   editor · multi · side (ajan, zaman çizelgesi, vocabulary, ürün paneli)
├── product/     Kayıtlı Senaryolar: ağaç, tablo, detay, yeni senaryo, başlıklar
├── api/         endpoints.ts (tek HTTP katmanı) · types.ts (sunucu sözleşmesi)
├── lib/         saf yardımcılar (gherkin, slug, scenarioProse, headings)
├── hooks/ history/ chat/ users/ shell/ ui/
├── root.css     React'in kendi temeli
└── theme.css    React'e özgü kurallar ve ölçek değişkenleri (--text-*, --space-*)
```

- **`../resources/static/app.css` paylaşımlıdır**, eski değil: `main.tsx`
  onu import eder; renk değişkenlerinin (`:root`) asıl yeri orası. Oradaki
  bir değişiklik iki arayüzü birden etkiler.
- `../resources/static/app.js` ve `index.html` **donduruldu**; dokunulmaz.

## Kurallar

- **Gerçek HTML:** tıklanan her şey `<button type="button">` ya da
  `<a href>`; tablolar `<table>` + `<th>`. Tıklanabilir `div`/`span` yok.
- **Klavye:** açılır menü Esc ile kapanır, ok tuşlarıyla gezilir; açılıp
  kapanan öğe `aria-expanded` taşır; combobox `role="combobox"` +
  `aria-activedescendant`. Örnekler: `product/ScenarioMoveMenu.tsx`,
  `product/HeadingCombobox.tsx`.
- **Satır içi menü** satırın tıklamasını tetiklememeli (`stopPropagation`),
  ve listenin altında yer yoksa yukarı açılmalı (`ScenarioTable.toggleMenu`).
- **Türkçe metin** Türkçe karakterle yazılır (ı ş ğ ü ö ç İ). Türkçe
  karşılaştırma / arama `lib/headings.ts` `foldTr` / `matchesTr` ile
  (büyük/küçük harf ve I/İ/ı farkı yok sayılır).
- **Tarayıcı depolaması** (`sessionStorage`/`localStorage`) yalnız kişisel
  kolaylık içindir ve her erişim `try/catch` içindedir.
- **Dış kaynak yok:** CDN, Google Fonts, uzak ikon seti yok. Kurumsal SSL
  denetimi engelliyor; her şey bundle'ın içinde olmalı. İkonlar inline,
  stroke'lu SVG; emoji ikon değildir.
- `[hidden]` her zaman gizler (`app.css` `!important` ile); `display` veren
  bir sınıf eklemek onu bozmaz.
- Paylaşılan bir CSS sınıfını silerken önce o sınıfı kullanan tüm TSX'i grep'le.

## Doğrulama

```bash
npx tsc --noEmit        # tip denetimi
npm run build           # tsc + vite build → ../resources/static/ui/
```

Derleme çıktısı git'te değil. VDI'da her `git pull`'dan sonra `npm run build`
çalışmazsa jar eski arayüzü sunar. Tarayıcıda deneme kullanıcının işidir;
görmeden "çalışıyor" denmez.
