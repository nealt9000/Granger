# Granger en español: guía para la revisión

Thank you for reviewing. Granger is an offline library app: encyclopedias, maps, books, audiobooks and music that live on the phone and work with no signal. Its English voice is plain and friendly, and its tagline is "The world's knowledge. Yours to keep." The Spanish should feel the same: neutral Latin American Spanish, *tú* for the user, nothing regional, short.

This is Claude's draft (Plan L2, Task 1). Please change anything that reads as a translation rather than as Spanish.

## How to see the app in Spanish

- **Now:** set the phone's language to Español (Settings → System → Languages). Granger follows it.
- **Once Plan L2 Task 3 lands:** Granger → Settings → Appearance → Language → Español, or on Android 13+ the phone's Settings → Apps → Granger → Language. Only Granger changes.
- Catalog copy (pack titles, knowledge areas such as "Medicine", use-case blurbs, the names of the Efficient and Comprehensive starts) and the packs' own content stay in English until Plan L3. You will see English there; that's expected.
- Dates, numbers and sizes ("1,5 GB", "10 ene 2025") are checked in Task 4, so leave them for now.

## Where the strings live

- `app/src/main/res/values-es/strings_<area>.xml`, one file per area of the app (home, search, player…).
- `core/{downloads,player,speech,archive}/src/main/res/values-es/strings.xml`.
- The English beside each, in `values/`, has a comment over any string whose use isn't obvious: what it labels and how much room it has.

A suggested change can be a comment in the review thread with the string's `name`, e.g. `home_add_feed: "Agregar fila"`.

## Glossary

Use these words everywhere. If you'd change one, change it here first: it touches many strings.

| English | Español | Notes |
| --- | --- | --- |
| Granger | Granger | Brand, never translated |
| Home | Inicio | Rail tool |
| Library | Biblioteca | |
| Browse | Explorar | Rail tool |
| Ask | Preguntar | Rail tool; "Ask (Preview)" is "Preguntar (versión preliminar)" |
| Listen | Escuchar | Rail tool |
| Search | Buscar | Verb on buttons; "Búsqueda" as a noun heading |
| Readiness | Preparación | |
| Get more | Conseguir más | |
| Sources | Fuentes | |
| Downloads | Descargas | |
| Settings | Ajustes | Android's own Spanish word |
| Supporter | Colaborador | |
| Granger Classics | Clásicos Granger | |
| pack | paquete | A Kiwix ZIM |
| book / e-book | libro / libro electrónico | |
| audiobook | audiolibro | |
| read aloud | leer en voz alta | "Lectura en voz alta" where English uses it as a noun |
| voice | voz | |
| bookmark | marcador | |
| highlight | resaltado | Verb: resaltar |
| note | nota | |
| chapter | capítulo | |
| Now playing | Reproduciendo | |
| Retry | Reintentar | |
| Remove | Quitar | Delete is Eliminar |
| Wi-Fi / mobile data | Wi-Fi / datos móviles | |
| Preview | Versión preliminar | |
| Tagline | "El conocimiento del mundo. Tuyo para siempre." | |

Words the draft added, used the same way everywhere:

| English | Español | Notes |
| --- | --- | --- |
| the librarian (Ask's AI) | el bibliotecario | |
| the rail (side bar of tools) | la barra | |
| pin / unpin | fijar / dejar de fijar | |
| feed (a row on Home) | sección | See below |
| playlist | lista (de reproducción) | Short form on tabs and menus |
| Contents (of a book) | Índice | |
| storage | almacenamiento | |
| phone | teléfono | Never *celular* or *móvil* |
| headphones | auriculares | |
| Get (a download button) | Descargar | |

## The strings Claude is least sure of

| Name | Draft | Why it's uncertain |
| --- | --- | --- |
| `shell_tool_ask`, and every sentence that names Ask | Preguntar | A verb as a tool's name makes sentences awkward. The draft writes around it: "Para usar Preguntar, necesitas…", "Índice para Preguntar", "Chat de Preguntar". Does that read naturally, or would a noun (*Consultas*) or quotes be better? |
| `onboarding_the_librarian` and others | el bibliotecario | Masculine as the generic. *La bibliotecaria*, or a neutral wording, may suit the brand better. |
| `home_add_feed` | Agregar sección | A feed is a row of suggestions on Home. *Fuente* would clash with Fuentes (Sources); *feed* is English. *Fila* is another option. |
| `home_kind_on_this_day` | Un día como hoy | The usual Spanish heading for "on this day in history". Check it reads as a feed's kind. |
| `home_drawing` | Eligiendo… | A feed's row while its cards are being drawn at random. |
| `home_strip_status` | `> %1$s aquí, todo listo sin conexión.` | The command-line status line (`> 11.5 GB here, ready offline.`). "Listos" would need to agree with GB, so the draft says "todo listo". |
| `onboarding_step_welcome` | Te damos la bienvenida | Gender-neutral, as Google writes it; *Bienvenido/a* is the alternative. |
| `onboarding_step_start`, `onboarding_start_sizes` | Elige un comienzo; El comienzo Eficiente… la biblioteca Completa | "Start" here is a starter library, and *inicio* is taken by Home. The start's names come from the catalog, in English until L3. |
| `onboarding_tier_library` | Un teléfono biblioteca | "A library phone": does the compound read naturally? |
| `player_top_under_voice`, `player_music_under_voice` | De fondo; Música de fondo | English "under the voice" means music playing quietly beneath the reading voice. *De fondo* is the natural phrase. |
| `player_sleep` | Temporizador | Short label under the sleep-timer icon. *Dormir* alone reads as an order. |
| `player_seek*` | Buscar posición… | TalkBack for the progress slider. |
| `research_best_guess_label` | …así que tómala con cautela. | English is the idiom "take it with a grain of salt". |
| `research_best_guess` | Mejor suposición | A button that shows the model's guess anyway. |
| `settings_ask_large` | Confirmar descargas de más de 100 MB | English "Ask before…" would be *Preguntar antes…*, which reads as the Ask tool. |
| `settings_section_listening` | Lectura y audio | "Reading & listening": *escucha* as a noun is stiff. |
| `settings_theme_field`, `settings_theme_night` | Campo (claro), Noche (oscuro) | Field and Night are the themes' names. *Campo* may not carry "field kit". |
| `settings_open_to_resume` | Donde lo dejé | An "Open to" choice: open where I left off. |
| `downloads_place_in_line` | %1$s en la cola | %1$s is the phone's own ordinal, "2.º". |
| `downloads_time_left_*`, `*_progress_left` | faltan unos 25 min; faltan aprox. 1 h; faltan menos de un minuto | "faltan menos de un minuto" is common speech, though "falta" is stricter. |
| `downloads_download_next` | Descargar a continuación | Moves a waiting download to the front. |
| `downloads_cancel_all_of` | Cancelar todo: %1$s | %1$s is an album's name or "Configuración inicial". |
| `archive_tab_installed`, `librivox_tab_installed` | Descargado | English says "Installed"; the tab lists what's been downloaded. |
| `archive_offer_comic` | Cómic: sus páginas, para hacer zoom | A comic as page images. |
| `music_sort_date_added` | Fecha en que se agregó | A sort order. |
| `audiobooks_missing` | No encontrado | A book whose files are gone. |
| `ebook_tab_gone`, `reader_tab_gone` | Este marcador se quitó. | The English says "bookmark", but this shows when the rail item that opened the page is gone. The draft follows the English; it may change. |
| `*_percent` | 25% | No space before %, as most Latin American apps write it; RAE prefers "25 %". |

## Where length forced a shorter wording

- **Rail labels** (`shell_tool_*`): Biblioteca (10 letters) and Preguntar (9) are the longest. Task 4 checks they fit.
- `shell_remove_from_rail`, `shell_pin_or_remove`: "la barra" rather than "la barra lateral".
- `music_tab_playlists`, `music_new_playlist` and the rest: "Listas", not "Listas de reproducción".
- `player_sleep`: "Temporizador", not "Temporizador de apagado", under the icon.
- `downloads_time_left_*`, `downloads_in_*`: min, h and s stay abbreviated, as in English.
- `readiness_show_all`: "Mostrar todo (%1$d)", which also avoids choosing a gender for "gaps".
- `onboarding_your_picks`: "Lo que elegiste" for "Your picks".
