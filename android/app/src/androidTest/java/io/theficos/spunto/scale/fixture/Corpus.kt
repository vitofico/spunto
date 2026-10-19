package io.theficos.spunto.scale.fixture

import kotlin.random.Random

/**
 * 10,000 synthetic notes, identical on every phone because the generator is seeded:
 * 60% Italian, 40% English, short ideas to long journal entries (about 500 characters on
 * average, the size 02 §4.2 budgets for), checklists, links and inline labels.
 *
 * The first notes are fixed probes for the folding checks; a few rare words are sprinkled
 * in so the benchmark has rare terms as well as common ones.
 */
object Corpus {
    const val SIZE = 10_000
    private const val SEED = 20261013L
    private const val EPOCH_2024 = 1_704_067_200_000L
    private const val TWO_YEARS_MS = 2 * 365 * 24 * 3600 * 1000L

    val probes = listOf(
        "Perché no? Un giro in bici fino a Ostia domenica mattina.",
        "CITTÀ INVISIBILI di Calvino: rileggere il capitolo sulle città sottili.",
        "Un’ottima trattoria: dall’Augustarello a Testaccio, coda alle 13.",
        "Straße der Pariser Kommune a Berlino: foto del murale per la tesi.",
        "Sistemare la ﬁnestra del bagno (la legatura ﬁ viene da un PDF copiato).",
        "Café de Flore? No, caffè al bar sotto casa con Giulia.",
    )

    class Note(val id: Long, val body: String, val updatedAt: Long)

    fun generate(): Sequence<Note> = sequence {
        val random = Random(SEED)
        val writer = Writer(random)
        for (i in 0 until SIZE) {
            val body = probes.getOrNull(i) ?: writer.note()
            yield(Note(id = i + 1L, body = body, updatedAt = EPOCH_2024 + random.nextLong(TWO_YEARS_MS)))
        }
    }
}

private class Writer(private val random: Random) {
    private fun <T> pick(items: List<T>): T = items[random.nextInt(items.size)]
    private fun chance(p: Double) = random.nextDouble() < p

    fun note(): String {
        val italian = chance(0.6)
        val body = when {
            chance(0.12) -> checklist(italian)
            chance(0.09) -> link(italian)
            else -> prose(italian)
        }
        val rare = if (chance(0.004)) "\n\n" + pick(rareSentences) else ""
        val labels = if (chance(0.6)) {
            "\n\n" + List(1 + random.nextInt(3)) { pick(if (italian) labelsIt else labelsEn) }.distinct().joinToString(" ")
        } else ""
        return body + rare + labels
    }

    private fun prose(italian: Boolean): String {
        val sentences = when (random.nextInt(100)) {
            in 0 until 30 -> 1 + random.nextInt(2)
            in 30 until 80 -> 3 + random.nextInt(8)
            else -> 12 + random.nextInt(33)
        }
        val pool = if (italian) italianSentences else englishSentences
        return List(sentences) { pick(pool)() }
            .chunked(2 + random.nextInt(3))
            .joinToString("\n\n") { it.joinToString(" ") }
    }

    private fun checklist(italian: Boolean): String {
        val heading = pick(if (italian) listsIt else listsEn)
        val items = List(3 + random.nextInt(8)) {
            val done = if (chance(0.3)) "x" else " "
            "- [$done] " + pick(if (italian) ingredientsIt + tasksIt else ingredientsEn + tasksEn)
        }.distinct()
        return heading + "\n" + items.joinToString("\n")
    }

    private fun link(italian: Boolean): String {
        val slug = List(2 + random.nextInt(3)) { pick(slugWords) }.joinToString("-")
        val pool = if (italian) italianSentences else englishSentences
        return "https://example.org/$slug\n" + List(1 + random.nextInt(2)) { pick(pool)() }.joinToString(" ")
    }

    private val italianSentences: List<() -> String> = listOf(
        { "Idea per ${pick(topicsIt)}: ${pick(ideasIt)}." },
        { "Domani devo sentire ${pick(names)} per ${pick(tasksIt)}." },
        { "Perché non provare ${pick(ideasIt)} prima di ${pick(daysIt)}?" },
        { "La riunione di ${pick(daysIt)} con ${pick(names)} è andata bene, però dobbiamo ancora decidere sul ${pick(featuresIt)}." },
        { "Ho letto ${pick(books)}: ${pick(opinionsIt)}" },
        { "Citazione da ${pick(books)}: «${pick(quotesIt)}»" },
        { "Fare il ${pick(tech)} del branch ${pick(branches)} sul server di staging." },
        { "Il bug della ${pick(featuresIt)} si presenta solo quando ${pick(conditionsIt)}." },
        { "Ricetta: ${pick(foodsIt)} con ${pick(ingredientsIt)} e ${pick(ingredientsIt)}, cottura ${5 + random.nextInt(40)} minuti." },
        { "Corsa di ${3 + random.nextInt(18)} km lungo il Tevere, passo ${4 + random.nextInt(3)}:${10 + random.nextInt(50)}." },
        { "A ${pick(places)} c’è ${pick(sightsIt)} che vale la pena vedere." },
        { "Comprare ${pick(ingredientsIt)}, ${pick(ingredientsIt)} e ${pick(ingredientsIt)}." },
        { "Pensiero: ${pick(thoughtsIt)}" },
        { "Più ci penso, più mi convince ${pick(ideasIt)}." },
        { "Già fatto: ${pick(tasksIt)}. Ancora da fare: ${pick(tasksIt)}." },
        { "Così com’è, il progetto non regge: ${pick(thoughtsIt)}" },
        { "Caffè con ${pick(names)} ${pick(daysIt)} alle ${8 + random.nextInt(12)}, parlare del progetto." },
        { "Viaggio a ${pick(places)}: treno alle ${6 + random.nextInt(14)}:${10 + random.nextInt(50)}, albergo vicino alla stazione." },
        { "Nota per la tesi: ${pick(thoughtsIt)}" },
        { "Riunione con ${pick(names)}: rivedere la ${pick(featuresIt)} e il ${pick(tech)}." },
        { "Oggi ho capito una cosa sul ${pick(tech)}: ${pick(thoughtsIt)}" },
        { "Spunto per ${pick(topicsIt)}: ${pick(ideasIt)}, magari con ${pick(names)}." },
    )

    private val englishSentences: List<() -> String> = listOf(
        { "Idea for ${pick(topicsEn)}: ${pick(ideasEn)}." },
        { "Call ${pick(names)} about ${pick(tasksEn)} before ${pick(daysEn)}." },
        { "Why not try ${pick(ideasEn)} first?" },
        { "Meeting with ${pick(names)} on ${pick(daysEn)}: we agreed to ship the ${pick(featuresEn)} after the ${pick(tech)} review." },
        { "Read a chapter of ${pick(books)}: ${pick(opinionsEn)}" },
        { "Quote from ${pick(books)}: \"${pick(quotesEn)}\"" },
        { "Deploy the ${pick(branches)} branch to staging and check the ${pick(tech)} logs." },
        { "The ${pick(featuresEn)} bug only shows up when ${pick(conditionsEn)}." },
        { "Recipe: ${pick(foodsEn)} with ${pick(ingredientsEn)} and ${pick(ingredientsEn)}, ${5 + random.nextInt(40)} minutes." },
        { "Ran ${3 + random.nextInt(18)} km along the river, pace ${4 + random.nextInt(3)}:${10 + random.nextInt(50)}." },
        { "Buy ${pick(ingredientsEn)}, ${pick(ingredientsEn)} and ${pick(ingredientsEn)}." },
        { "Thought: ${pick(thoughtsEn)}" },
        { "Open a pull request for the ${pick(featuresEn)} once the ${pick(tech)} tests pass." },
        { "The meeting ran long; next time send the agenda the day before." },
        { "Book idea: a chapter on ${pick(ideasEn)}." },
        { "Note to self: ${pick(thoughtsEn)}" },
        { "Project ${pick(topicsEn)} needs ${pick(ideasEn)} before the release." },
    )

    private val names = listOf("Marco", "Giulia", "Luca", "Sara", "Andrea", "Francesca", "Paolo", "Chiara", "Matteo", "Elena", "Davide", "Laura")
    private val daysIt = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    private val daysEn = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val places = listOf("Roma", "Milano", "Napoli", "Torino", "Bologna", "Firenze", "Matera", "Lecce", "Trieste", "Ostia", "Trastevere", "Lisbona")
    private val topicsIt = listOf("l’app", "il progetto", "la casa", "il blog", "la newsletter", "il viaggio", "la tesi", "il podcast", "il giardino", "la palestra")
    private val topicsEn = listOf("the app", "the side project", "the blog", "the newsletter", "the trip", "the talk", "the garden", "the home lab")
    private val ideasIt = listOf("un widget per le note veloci", "una mappa delle fontanelle", "un bot che riassume le riunioni", "raccogliere le ricette della nonna", "un diario di corsa", "una lista di libri da regalare", "etichette automatiche per le foto", "un timer per lo studio", "promemoria legati al luogo", "un archivio delle bollette", "una app per dividere le spese")
    private val ideasEn = listOf("a widget for quick notes", "a map of drinking fountains", "a bot that summarises meetings", "a reading list to give away", "automatic labels for photos", "location-based reminders", "a running log", "an app to split expenses", "offline search on the phone")
    private val tasksIt = listOf("rinnovare il passaporto", "pagare la bolletta della luce", "prenotare il dentista", "chiamare l’idraulico", "rispondere alla mail del condominio", "aggiornare il curriculum", "sistemare il backup del NAS", "comprare il regalo per Giulia", "riportare i libri in biblioteca", "fare il tagliando alla macchina")
    private val tasksEn = listOf("renew the passport", "pay the electricity bill", "book the dentist", "update the CV", "fix the NAS backup", "return the library books", "service the car", "reply to the landlord")
    private val foodsIt = listOf("risotto", "carbonara", "amatriciana", "lasagna", "parmigiana", "tiramisù", "pesto", "minestrone", "frittata", "caponata")
    private val foodsEn = listOf("risotto", "banana bread", "chili", "pancakes", "curry", "lentil soup", "granola")
    private val ingredientsIt = listOf("latte", "pane", "uova", "burro", "farina", "zucchero", "pomodori", "basilico", "parmigiano", "guanciale", "pecorino", "zucchine", "melanzane", "caffè", "mozzarella", "olio", "riso", "cipolle", "aglio", "limoni")
    private val ingredientsEn = listOf("milk", "bread", "eggs", "butter", "flour", "sugar", "tomatoes", "basil", "parmesan", "coffee", "rice", "onions", "garlic", "lemons", "oats", "spinach")
    private val tech = listOf("deploy", "server", "database", "backup", "release", "cluster", "container", "pipeline", "API", "token", "cache", "test", "Kubernetes")
    private val branches = listOf("main", "feature/search", "fix/sync", "release", "feature/widget", "spike/fts")
    private val featuresIt = listOf("sincronizzazione", "ricerca", "notifica", "esportazione", "pagina di login", "modalità scura", "schermata iniziale")
    private val featuresEn = listOf("sync", "search", "notification", "export", "login page", "dark mode", "home screen", "widget")
    private val conditionsIt = listOf("il telefono è offline", "la nota è più lunga di mille righe", "si cambia lingua a metà", "il server risponde lento", "l’app torna in primo piano")
    private val conditionsEn = listOf("the phone is offline", "the note is very long", "the language changes mid-sentence", "the server is slow", "the app comes back to the foreground")
    private val books = listOf("Le città invisibili", "Il nome della rosa", "Se una notte d’inverno un viaggiatore", "Il barone rampante", "La coscienza di Zeno", "Thinking, Fast and Slow", "Deep Work", "The Pragmatic Programmer", "Atomic Habits", "Designing Data-Intensive Applications")
    private val opinionsIt = listOf("bello, ma la seconda metà è lenta.", "da rileggere con calma.", "consigliato da Chiara, e aveva ragione.", "il capitolo sulla memoria è il migliore.", "non mi ha convinto.")
    private val opinionsEn = listOf("worth a second read.", "the middle drags a bit.", "recommended by Paolo, and he was right.", "the chapter on habits is the best one.", "not convinced yet.")
    private val quotesIt = listOf("La memoria è ridondante: ripete i segni perché la città cominci a esistere.", "Chi va piano va sano e va lontano.", "Non si finisce mai di imparare.", "Il tempo è galantuomo.")
    private val quotesEn = listOf("Make it work, make it right, make it fast.", "Plans are useless, but planning is indispensable.", "The best time to plant a tree was twenty years ago.", "Simple things should be simple.")
    private val thoughtsIt = listOf("scrivere subito è meglio che ricordare dopo.", "le note brevi si ritrovano più facilmente.", "serve meno struttura e più ricerca.", "la sera rileggo quello che ho scritto la mattina.", "le etichette funzionano solo se sono poche.", "meglio un’idea imperfetta oggi che una perfetta mai.")
    private val thoughtsEn = listOf("writing it down now beats remembering it later.", "short notes are easier to find again.", "less structure, better search.", "labels only work when there are few of them.", "an imperfect idea today beats a perfect one never.")
    private val sightsIt = listOf("un mercato coperto", "una libreria antica", "un belvedere", "una trattoria storica", "un museo piccolo", "una pasticceria siciliana")
    private val listsIt = listOf("Lista della spesa", "Da fare", "Cose da portare", "Regali di Natale")
    private val listsEn = listOf("Shopping list", "To do", "Packing list", "Weekend")
    private val labelsIt = listOf("#idea", "#lavoro/spunto", "#lavoro/cluster", "#lettura", "#ricette", "#todo", "#viaggi/roma", "#corsa", "#casa", "#tesi")
    private val labelsEn = listOf("#idea", "#project/spunto", "#reading", "#dev", "#health", "#todo", "#travel", "#recipes")
    private val slugWords = listOf("offline", "search", "sqlite", "android", "notes", "speech", "kotlin", "ricette", "roma", "design", "privacy", "backup")
    private val rareSentences = listOf(
        "Idea per il weekend: mappa dei nasoni lungo il Tevere.",
        "Provare Obtainium per gli aggiornamenti delle app.",
        "Read about the Zettelkasten method again; maybe one note per idea.",
        "L’ornitorinco del documentario era incredibile.",
    )
}
