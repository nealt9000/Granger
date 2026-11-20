# Play listing + Data safety for the online build (draft, 2026-11-19, Neal approves)

Checked against the live public listing (en-US and es-419, "Updated Nov 12") and opengranger.org/privacy (19 Nov).

## What's wrong live

- Full description, intro: "nothing you look up leaves your phone" / "nada de lo que consultas sale de tu teléfono". **False since online is the default.**
- Full description, Privacy: "Nothing you read or search leaves the device." / "Nada de lo que lees o buscas sale del dispositivo." **False.**
- "What's inside" predates Explore, topic pages, linked Classics, Keep, Local only, Outfitting; "Home: something worth reading every day" is the old Home.
- Short description ("…Works with no signal.") is still true for what you keep. Fine as is, or use the new one below.
- Data safety: "No data collected · No data shared". Probably no longer right (below).

## Short description (≤80)

EN (79): `Wikipedia, medicine, survival, DIY and courses: one searchable offline library.`
(Earlier: `An encyclopedia to wander: Wikipedia and classic books, online or offline.`)
ES (79): `Una enciclopedia para recorrer: Wikipedia y libros clásicos, en línea o sin conexión.` — 84, too long; use:
ES (78): `Wikipedia, medicina, supervivencia y cursos: una sola biblioteca sin conexión.`
(Earlier: `Una enciclopedia para recorrer: Wikipedia y clásicos, con o sin conexión.`)

## Full description — English

Granger is an encyclopedia to wander, built from Wikipedia and Kiwix, that you can keep on your phone. Start reading at once, online, with nothing to download. Keep what you like, and it works on a plane, at a campsite, and through outages.

Beyond Wikipedia, Granger brings the Kiwix catalog's medicine, survival, DIY and courses into one library: one search covers it all, and Explore sorts it by subject.

What's inside
• Explore: seventeen subjects, 108 rooms and every era. Tap an article and a topic page opens in front of it: its time, the people alive alongside it, the books on your phone that name it, and the closest topics.
• Wikipedia and reference works, with pictures, in English and Spanish. Keep an article, a whole room or a pack with one tap.
• 622 classic books, typeset, whose people and places link to their articles, plus your own ebooks. Read aloud by Granger's own voices, offline.
• One search over everything: titles, article text, the pages of every book, your music and audiobooks, and Wikipedia online.
• A timeline of everything you have, Wander, and small games dealt from your library.
• Local only shows exactly what your phone holds, as with no signal. Outfitting shows what works offline and fills the gaps.

Privacy
No account, no ads, no analytics. Granger has no server and never sees what you read. Reading online asks Wikipedia (and other free libraries) for each page, so they see what you open. Choose Never go online in Settings and the app makes no network requests at all. Feedback reaches us only if you choose to send it.

Early access
This is a closed-test build. Library packs are large downloads and wait for Wi-Fi unless you say otherwise. Tell us what's missing: feedback@opengranger.org

Granger is open source (GPL-3.0). More at https://opengranger.org

## Descripción completa — español (es-419)

Granger es una enciclopedia para recorrer, hecha con Wikipedia y Kiwix, que puedes guardar en tu teléfono. Empieza a leer de inmediato, en línea, sin descargar nada. Guarda lo que quieras, y funciona en un avión, en un campamento y durante apagones.

Además de Wikipedia, Granger reúne la medicina, la supervivencia, el bricolaje y los cursos del catálogo de Kiwix en una sola biblioteca: una búsqueda lo abarca todo y Explorar lo ordena por temas.

Qué incluye
• Explorar: diecisiete temas, 108 salas y todas las épocas. Toca un artículo y se abre delante su página de tema: su época, las personas que vivieron a la par, los libros de tu teléfono que lo nombran y los temas más cercanos.
• Wikipedia y obras de consulta, con imágenes, en español y en inglés. Guarda un artículo, una sala entera o un paquete con un toque.
• 622 libros clásicos, bien compuestos, cuyas personas y lugares enlazan con sus artículos, y tus propios libros electrónicos. Leídos en voz alta sin conexión; por ahora la voz de Granger lee en inglés y los textos en español los lee la voz del teléfono.
• Una sola búsqueda en todo: títulos, texto de los artículos, las páginas de cada libro, tu música y audiolibros, y Wikipedia en línea.
• Una línea de tiempo de todo lo que tienes, Pasear y pequeños juegos sacados de tu biblioteca.
• Solo local muestra exactamente lo que hay en tu teléfono, como sin señal. Equipamiento muestra qué funciona sin conexión y completa lo que falta.

Privacidad
Sin cuenta, sin anuncios, sin análisis de uso. Granger no tiene servidor y nunca ve lo que lees. Leer en línea pide cada página a Wikipedia (y a otras bibliotecas libres), así que ven lo que abres. Elige No conectarse nunca en Ajustes y la app no hace ninguna petición a la red. Tus comentarios nos llegan solo si decides enviarlos.

Acceso anticipado
Esta es una versión de prueba cerrada. Los paquetes de la biblioteca son descargas grandes y esperan al Wi-Fi salvo que digas lo contrario. Cuéntanos qué falta: feedback@opengranger.org

Granger es de código abierto (GPL-3.0). Más en https://opengranger.org


## Data safety

Google's rules (support answer 10787469, read 2026-11-19):
- "Collect" = transmitting data from your app off the device, whether to you or a third party.
- Not declared only if on-device, end-to-end encrypted, or users navigating the open web in a webview.
- Sharing is exempt for "a specific user-initiated action, where the user reasonably expects the data to be shared", or after a prominent in-app disclosure and consent.

Granger's own code (WikimediaClient, not a webview on the open web) sends search terms and page titles to Wikipedia. So the conservative, defensible answer:

- **Collects data: Yes.**
  - App activity → **In-app search history** (online search goes to Wikipedia).
  - App activity → **Other actions** (which articles and pictures are opened online). Alternative reading: Web browsing history; Other actions fits better because it's in-app reading, not websites.
  - For each: collected (not shared, see below), **processed ephemerally: No** (Granger can't vouch for Wikimedia's logs), **optional: Yes** (Settings → Never go online; the app works on what's kept), purpose **App functionality**.
- **Shares data: No.** Exempt: user-initiated reading, with the privacy line shown before anything goes online.
- **Encrypted in transit: Yes** (HTTPS only).
- **Deletion request: No** — Granger holds nothing to delete; Wikimedia's policy covers its servers.
- Send feedback stays as before (email the user sends themselves).

The public card would then say "Data collected: App activity" instead of "No data collected". The alternative (keep "No data collected", argue Granger itself collects nothing) contradicts Google's definition above.
