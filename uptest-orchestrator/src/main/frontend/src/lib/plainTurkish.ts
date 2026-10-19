/*
  Bir Gherkin adımını analistin cümlesine çevirir.

  Kayıtlı Senaryolar ekranı senaryoyu iki dilde gösteriyor: solda çalışan
  Gherkin, sağda okunan Türkçe. İkincisi, kod okumayan bir analistin "bu
  senaryo ne yapıyor" sorusunu Gherkin öğrenmeden cevaplayabilmesi için var.

  DETERMINISTIK VE ÇEVİRİSİZ. Burada model yok: her desen için elle yazılmış
  bir cümle kalıbı var, argümanlar yerine konur. Bir LLM'e çevirtmek iki şeyi
  birden bozardı -- aynı senaryo her açılışta farklı okunurdu, ve asıl kötüsü,
  adımın YAPMADIĞI bir şeyi söyleyebilirdi. Bu panel senaryoyu açıklıyor,
  yorumlamıyor.

  Tanımadığı bir adım OLDUĞU GİBİ gösterilir. Uydurmak yerine ham satırı
  basmak, sözlük büyüdüğünde bu dosyanın geride kalmasını zararsız kılar:
  okunabilirlik kaybolur, doğruluk kaybolmaz.
*/

/** Pattern → Turkish sentence. `$0`, `$1` are the quoted/int arguments. */
const SENTENCES: ReadonlyArray<readonly [string, string]> = [
  ["login with cif {string} and userid {string}",
    "$0 müşteri numarası ve $1 kullanıcı kodu ile giriş yapılır"],
  ["login to ngi with cif {string}",
    "$0 müşteri kodu ile bireysel şubeye giriş yapılır"],

  ["go to {string}", "$0 adresi açılır"],
  ["navigate to {string} {string}", "$0 › $1 menüsüne gidilir"],
  ["click {string}", "“$0” tıklanır"],

  ["enter {string} {string}", "“$0” alanına “$1” yazılır"],
  ["enter date {string} {string}", "“$0” alanına $1 tarihi girilir"],
  ["enter amount {string} {string}", "“$0” alanına $1 tutarı girilir"],

  // Argument order is value-first here: `select "TL" from "Döviz Cinsi"`.
  // Reading it the other way round would describe the opposite action, which
  // is the same trap the validator's `roles()` exists to avoid.
  ["select {string} from {string}", "“$1” alanından “$0” seçilir"],
  ["select account {string} from {string}", "“$1” alanından $0 numaralı hesap seçilir"],
  ["select account at index {int} from {string}", "“$1” alanından $0. sıradaki hesap seçilir"],

  ["upload {string} {string}", "“$0” alanına “$1” dosyası yüklenir"],
  ["check {string}", "“$0” kutusu işaretlenir"],
  ["choose {string}", "“$0” seçeneği işaretlenir"],
  ["select row {string}", "“$0” satırı seçilir"],
  ["select row at index {int}", "$0. satır seçilir"],

  ["verify the {string} field equals {string}", "“$0” alanının “$1” olduğu doğrulanır"],
  ["verify the {string} has amount {string}", "“$0” tutarının $1 olduğu doğrulanır"],
  ["verify the {string} has date {string}", "“$0” tarihinin $1 olduğu doğrulanır"],
  ["verify the {string} field is masked", "“$0” alanının maskeli göründüğü doğrulanır"],
  ["verify {string} text is displayed", "“$0” metninin ekranda göründüğü doğrulanır"],

  ["wait {string} seconds", "$0 saniye beklenir"],
];

/** Built once: each pattern as an anchored regex with capturing groups. */
const MATCHERS = SENTENCES.map(([pattern, sentence]) => ({
  sentence,
  regex: new RegExp(
    "^"
    + pattern
      .replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
      // The escape above turned {string} into \{string\}; put the groups back.
      .replace(/\\\{string\\\}/g, '"([^"]*)"')
      .replace(/\\\{int\\\}/g, "(\\d+)")
    + "$",
  ),
}));

const KEYWORDS = /^(Given|When|Then|And|But|Diyelim ki|Eğer ki|O zaman|Ve|Fakat)\s+/;

/** One rendered step: its keyword and the sentence. */
export interface PlainStep {
  keyword: string;
  text: string;
  /** False when no pattern matched and `text` is the raw Gherkin line. */
  translated: boolean;
}

/**
 * The step lines of a feature, as Turkish sentences.
 *
 * Reads the raw text rather than an AST: the registry already parsed this
 * file server-side for its title and step count, and asking the browser to
 * carry a second parser for a reading aid is not worth it. A line that is
 * not a step — Feature, Scenario, a tag, a comment, the description — simply
 * does not start with a keyword and is skipped.
 */
export function plainSteps(feature: string): PlainStep[] {
  const steps: PlainStep[] = [];
  for (const raw of feature.split(/\r?\n/)) {
    const line = raw.trim();
    const keyword = KEYWORDS.exec(line);
    if (!keyword) continue;

    const body = line.slice(keyword[0].length).trim();
    steps.push({ keyword: keyword[1] ?? "", ...render(body) });
  }
  return steps;
}

function render(body: string): { text: string; translated: boolean } {
  for (const { regex, sentence } of MATCHERS) {
    const found = regex.exec(body);
    if (!found) continue;
    return {
      text: sentence.replace(/\$(\d)/g, (_, slot) => found[Number(slot) + 1] ?? ""),
      translated: true,
    };
  }
  return { text: body, translated: false };
}
