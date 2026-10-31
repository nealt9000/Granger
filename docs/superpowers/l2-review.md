# Granger en español: guía para la revisión

Thank you for reviewing. Granger is an offline library app: encyclopedias, maps, books, audiobooks and music that live on the phone and work with no signal. Its English voice is plain and friendly, and its tagline is "The world's knowledge. Yours to keep." The Spanish should feel the same: neutral Latin American Spanish, *tú* for the user, nothing regional, short.

This is Claude's draft (Plan L2, Task 1). Please change anything that reads as a translation rather than as Spanish.

## How to see the app in Spanish

- **Just Granger:** Granger → Settings → Appearance → Language → Español. On Android 13+ the phone's Settings → Apps → Granger → Language does the same. The rest of the phone stays as it is.
- **The whole phone:** set the phone's language to Español (Settings → System → Languages). Granger follows it while its Language is "Follow the phone".
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
| searchable in full | buscable por completo | Search and Ask can read a book's whole text |
| media type (Books, Music…) | tipo de contenido | |
| set (a preset; an archive.org multi-part item) | conjunto | *Paquete* is taken by packs |
| collection | colección | |
| pictures (a pack's flavour) | imágenes | "Con imágenes", "Sin imágenes" |
| covers (a bigger pack holds a smaller) | incluye | |

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
| `settings_language_system` | Igual que el teléfono (Español) | Matches the theme's "Igual que el teléfono". The language in brackets is the phone's, named in itself. |
| `*_percent` | 25% | No space before %, as most Latin American apps write it; RAE prefers "25 %". |
| `library_*searchable*`, `library_full_text` | Hacer todos buscables…; Buscable por completo; 312 buscables por completo | *Buscable* is used in software but isn't in every dictionary. The alternative is longer: "se puede buscar en todo el texto". |
| `library_new_media_type` | Nuevo tipo de contenido | A top-level group such as Books or Music. *Tipo de medio* is the literal, but reads as technical. |
| `sources_preset_title`, `archive_large_set` | Conjunto %1$s ("Conjunto Clásicos Granger") | A ready-made set of downloads. Does *conjunto* read naturally before a name? |
| `sources_pictures_only` | Con imágenes (única opción) | English "With pictures only". Spanish can't put *solo* before a capitalised choice name, so the draft puts a note after it. |
| `sources_get_smaller` | Descargar la versión sin imágenes, 7,0 GB | English "Get no pictures, 7.0 GB"; *la versión* was added so the flavour reads as a noun. |
| `sources_adds_*` | Amplía tu %1$s (12% nuevo); Agrega unos 4.000 artículos a… | A Wikipedia subject's share of new articles. "(12% nuevo)" is terse; "(12% son nuevos)" is the fuller form. |
| `sources_tidy_text` | Tus lugares y marcadores se trasladan. | English "move across" to the bigger pack. |
| `sources_link_models_sub` | Lo que hace funcionar Preguntar y la lectura en voz alta | English "What Ask and Read aloud run on". |
| `sources_all` | Todas | The chip that shows every category; agrees with *categorías*. |
| `onboarding_held*`, `readiness_covers*` | Wikipedia Top 1 million incluye Best 50,000… | The slice names come from the catalog, in English until L3, so the sentence mixes languages for now. |
| `ebook_pdf_reads_badly` | …(%1$s), así que se queda como páginas. | %1$s is the converter's reason, still in English ("no chapters"). |
| `media_unmounted` | Vuelve a conectarlo para terminar, o quita la descarga primero. | English "Put it back": the storage is usually an SD card, so *insertar* may be better. |

## Where length forced a shorter wording

- **Rail labels** (`shell_tool_*`): Biblioteca (10 letters) and Preguntar (9) are the longest. Task 4 checks they fit.
- `shell_remove_from_rail`, `shell_pin_or_remove`: "la barra" rather than "la barra lateral".
- `music_tab_playlists`, `music_new_playlist` and the rest: "Listas", not "Listas de reproducción".
- `player_sleep`: "Temporizador", not "Temporizador de apagado", under the icon.
- `downloads_time_left_*`, `downloads_in_*`: min, h and s stay abbreviated, as in English.
- `readiness_show_all`: "Mostrar todo (%1$d)", which also avoids choosing a gender for "gaps".
- `onboarding_your_picks`: "Lo que elegiste" for "Your picks".

Found in Task 4's Spanish theme shots (`ThemeShotsSpanish`), and shortened:

- `sources_family_devdocs`: "Programación", not "Documentación para desarrolladores", which took three lines as a screen title and two in its search box. English is "Developer docs".
- `sources_family_stack_exchange`: "Preguntas y respuestas", not "Sitios de preguntas y respuestas", for the same reason. English is "Q&A sites".
- `sources_tab_updates`: "Actualizar", not "Actualizaciones", which broke mid-word in a third of the screen's width. A verb as a tab's name: does it read as "the ones to update"?
- `library_sort_recent`: "Recientes", as on Audiobooks, not "Leídos hace poco", in a row of sort chips.
- `music_shuffle_all`: "Todo aleatorio", not "Reproducir todo al azar", which pushed "Ordenar: Título" into a column of word pieces.
- The Library's title, "Biblioteca", shrinks slightly to fit beside its four buttons rather than end in "…"; the Get more tabs ("Descargado") shrink the same way. English is unchanged.

Still English in Spanish, by design until L3: catalog copy, and the names of the collections the app files books into ("Granger Classics", "My books", "Archive.org"), which are stored in the library when the collection is made.

## Plan L3: Spanish knowledge

New strings from L3 (the library's languages, Spanish packs and Clásicos Granger), with what Claude was least sure of.

- `settings_language_library`: "Idiomas de tu biblioteca". The library's languages are the languages of what you download (packs, starts, Readiness), not the app's text.
- `settings_language_library_blurb`: "Los comienzos, Preparación y Conseguir más los usan. El primero es el principal." "Comienzos" follows L2's "Elige un comienzo"; "inicio" is Home in the glossary.
- `settings_language_main` / `settings_language_make_main`: "Idioma principal" / "Hacer principal". Is "Hacer principal" natural for a small button, or is "Usar como principal" better?
- `onboarding_also_language`: "¿También en %1$s?", where %1$s is the language in lower case ("¿También en inglés?"). It's a checkbox on Welcome, shown only on a phone set to two languages.
- `onboarding_also_language_blurb`, `onboarding_second_blurb`: "Suma su enciclopedia a tu comienzo…". "Suma" or "Agrega"?
- `onboarding_second_encyclopedia`: "Enciclopedia en %1$s" ("Enciclopedia en inglés").
- `readiness_no_packs_in`: "Todavía no hay paquetes en %1$s para esta área" ("…en español…"). "Área" for a Readiness domain, as the English says "area".
- `sources_all_languages`: "Todos los idiomas", a chip over the Get more lists.
- Catalog copy (from `tools/catalog-gen/curated.json` and `pack_sets.json`, not the app's resources) is listed in Plan L3 Task 3 for the same review: domain titles, "Comienzo eficiente", "Biblioteca completa", use-case titles and blurbs, the Spanish Wikipedia set's labels.
- **Glossary change (L3 Task 8):** "Clásicos Granger" is now the Spanish books' own collection, so the English collection keeps its English name, "Granger Classics", in a Spanish app (`onboarding_granger_classics`, and the collection's stored name). `library_replaced_by_classics` ("…de Clásicos Granger") still names the English books; it only shows beside the old English Classics pack.
- `library_collection_my_books`, `library_collection_other`, `library_collection_on_this_phone`: "Mis libros", "Otros", "En este teléfono", shown in place of the English names the library stored when it made those collections.
