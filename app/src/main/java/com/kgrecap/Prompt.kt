package com.kgrecap

object Prompt {
    const val TEXT = """[INSTRUCTION FOR AI TRANSLATOR]
Translate each numbered dialogue part below from speech recognition output into natural spoken Myanmar (မြန်မာ) for a drama scene read aloud by a text-to-speech (TTS) voice-over. Write as natural character dialogue, not as a narrator. Use neighbouring parts to infer unclear or cut-off speech.
Rules:
 * Keep every part separate. Never merge, split, or move lines between parts.
 * Keep each header line exact, including times, syllable limit, and speaker (for example [Part 3 • 00:20 - 00:25 • 3.8s • max 21 syllables • speaker: male]).
 * Under each header, write only the translated spoken line in Myanmar Unicode script.
 * Use Myanmar script only. Never include English letters or digits. Spell out numbers and foreign words/names in Myanmar script (for example, 5000 -> ငါးထောင်).
 * Output clean text only. No markdown, no speaker labels, no stage directions, no parentheses, no brackets, no ellipsis (...), no emoji, no symbols, no greetings, no notes, and no explanations.
 * Separate parts with this exact line: ----------------------------------------
 * Return only the translated parts.
Timing & TTS rules:
8. Stay within the given seconds and at or under the syllable limit.
9. If too long, shorten while preserving meaning, facts, names, and emotion. Drop filler and repetition. Shorter is acceptable.
10. Use ၊ for natural short pauses inside a sentence (roughly every 6 to 12 syllables) and end every sentence with ။. Never write long run-on lines without punctuation.
11. Do not add politeness endings such as ရှင်, ရှင့်, ခင်ဗျာ, ဗျာ, ကွယ်, နော်.
12. Do not add facts or meanings not supported by the original line or immediate context. For cut-off or unclear fragments, give a brief, natural spoken Myanmar equivalent fitting the context and syllable limit.
Speaker rules:
13. Follow the header's speaker tag (male, female, or unknown) without altering the header.
14. For male speakers, use male first-person pronouns (ကျွန်တော်, ကျနော်, ငါ, ကိုယ်) and never female ones (ကျွန်မ, ကျမ). For female speakers, use female first-person pronouns (ကျွန်မ, ကျမ, ငါ, ကိုယ်) and never male ones. For unknown speakers, avoid gendered first-person pronouns when possible.
15. Match tone and phrasing (plain neutral, urgent, or casual) to the scene without using forbidden endings from rule 11.
[ဇာတ်ကောင်စကားပြော စည်းကမ်းများ — မဖြစ်မနေ လိုက်နာရန်]
၁။ ဇာတ်ကြောင်းပြောဟန် မဟုတ်ဘဲ ဇာတ်ကောင်အစစ် ပြောနေသလို သဘာဝကျပြီး ဒရမ်မာဆန်သော စကားပြောဟန် ဖြစ်ရမည်။
၂။ အပိုင်းတစ်ပိုင်းနှင့် တစ်ပိုင်းကြား အဓိပ္ပာယ်နှင့် စီးဆင်းမှု အချိတ်အဆက် မိရမည်။
၃။ မြန်မာစာသက်သက်သာ ဖြစ်ရမည်။ အင်္ဂလိပ်စာလုံးနှင့် ဂဏန်းများ လုံးဝ မပါရ။ နိုင်ငံခြားအမည်များ၊ ဝေါဟာရများနှင့် ကိန်းဂဏန်းများကို မြန်မာအသံထွက်/စာလုံးပေါင်းဖြင့်သာ ရေးရမည်။
၄။ Markdown၊ ပြောသူအမည်၊ ကွင်းစကွင်းပိတ်၊ အမူအရာညွှန်ပြချက်၊ နှုတ်ခွန်းဆက်နှင့် ရှင်းလင်းချက်များ လုံးဝ မပါစေရ။
[INPUT TRANSCRIPT PARTS TO TRANSLATE]"""
}
