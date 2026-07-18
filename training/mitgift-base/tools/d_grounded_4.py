# Grounded-Lane Batch 4: Koerper/Biologie-Basics (keine Medizin-Ratschlaege/Dosierungen),
# Musik-Grundlagen, Sprache/Woerter, Spiele/Sport-Regeln.
# ROWS = [(thema, fakten, user, assistant, lang)], lang "de"/"en".
ROWS = [
    # --- Koerper/Biologie-Basics ---
    (
        "Herzschlag",
        "Das menschliche Herz schlägt in Ruhe etwa 60 bis 80 Mal pro Minute. Es pumpt Blut "
        "durch ein Netz aus Adern, das aneinandergelegt mehrere Zehntausend Kilometer lang "
        "wäre. Das Herz ist ein Muskel, der ohne Pause ein Leben lang arbeitet.",
        "Wie oft schlägt eigentlich mein Herz?",
        "Etwa 60 bis 80 Mal in der Minute, wenn du ruhig sitzt — dein Herz nimmt sich dabei nie "
        "eine Pause. Und die Adern, durch die es das Blut schickt, wären aneinandergelegt "
        "tausende Kilometer lang.",
        "de",
    ),
    (
        "Lungenoberfläche",
        "Pro Atemzug atmet ein Erwachsener in Ruhe etwa einen halben Liter Luft. Durch die "
        "vielen kleinen Lungenbläschen kommt im Inneren der Lunge eine sehr große Fläche "
        "zusammen, oft mit der Größe eines Tennisplatzes verglichen.",
        "Wie groß ist eigentlich meine Lunge von innen?",
        "Größer, als man denkt: durch die vielen kleinen Lungenbläschen kommt da drin ungefähr "
        "die Fläche eines Tennisplatzes zusammen. So kriegt der Sauerstoff mit jedem Atemzug "
        "viel Platz zum Andocken.",
        "de",
    ),
    (
        "Skelett",
        "Erwachsene Menschen haben 206 Knochen. Babys werden mit etwa 300 Knochen geboren, "
        "viele davon wachsen im Lauf der Kindheit zusammen. Der Oberschenkelknochen ist der "
        "längste und stabilste Knochen im Körper.",
        "Wie viele Knochen hat ein Mensch eigentlich?",
        "Als Erwachsener etwa 206. Als Baby sind es noch mehr, so um die 300 — ein paar wachsen "
        "später einfach zusammen. Der längste von allen sitzt im Oberschenkel.",
        "de",
    ),
    (
        "Haut als Organ",
        "Die Haut ist das größte Organ des Menschen, bei Erwachsenen etwa 1,5 bis 2 "
        "Quadratmeter groß. Sie erneuert sich ständig: alte Hautzellen lösen sich ab, neue "
        "wachsen von unten nach.",
        "Was ist eigentlich das größte Organ, das wir haben?",
        "Die Haut, mit klarem Abstand — bei einem Erwachsenen locker anderthalb bis zwei "
        "Quadratmeter. Und sie erneuert sich ständig selbst, ganz leise, ohne dass wir es "
        "merken.",
        "de",
    ),
    (
        "Fünf Sinne",
        "Klassisch werden dem Menschen fünf Sinne zugeschrieben: Sehen, Hören, Riechen, "
        "Schmecken und Tasten. Manche Fachleute zählen weitere dazu, etwa den "
        "Gleichgewichtssinn, der im Innenohr sitzt.",
        "Wie viele Sinne haben wir eigentlich?",
        "Klassisch zählt man fünf: Sehen, Hören, Riechen, Schmecken, Tasten. Manche zählen noch "
        "den Gleichgewichtssinn dazu — der sitzt übrigens im Ohr, nicht in den Augen.",
        "de",
    ),
    (
        "Gehirngewicht",
        "Das menschliche Gehirn wiegt etwa 1,3 bis 1,4 Kilogramm und besteht aus Milliarden "
        "Nervenzellen, die sich gegenseitig Signale schicken. Es verbraucht dabei "
        "überraschend viel Energie im Vergleich zu seinem Gewicht.",
        "Wie schwer ist eigentlich ein menschliches Gehirn?",
        "Etwa anderthalb Kilo, grob geschätzt. Klingt nicht viel, aber da drin feuern Milliarden "
        "Nervenzellen ständig Signale hin und her — für sein Gewicht ist es ein echter "
        "Energiefresser.",
        "de",
    ),
    (
        "Fingerabdrücke",
        "Fingerabdrücke sind bei jedem Menschen einzigartig, sogar eineiige Zwillinge haben "
        "unterschiedliche Muster. Sie bilden sich schon vor der Geburt und bleiben ein Leben "
        "lang gleich.",
        "Stimmt das, dass jeder Mensch einen anderen Fingerabdruck hat?",
        "Ja, wirklich jeder. Sogar eineiige Zwillinge, die sonst fast identisch sind, "
        "unterscheiden sich da. Die Muster bilden sich schon vor der Geburt und bleiben dann "
        "für immer gleich.",
        "de",
    ),
    (
        "Niesreflex",
        "Beim Niesen presst der Körper Luft mit hoher Geschwindigkeit aus der Nase, um "
        "Fremdkörper oder Reizstoffe loszuwerden. Es ist ein automatischer Reflex, den man "
        "kaum bewusst unterdrücken kann.",
        "Warum muss man eigentlich niesen?",
        "Weil deine Nase gerade großreinemacht: Sie presst Luft mit ordentlich Tempo raus, um "
        "Staub oder Reizstoffe loszuwerden. Ein Reflex, den du kaum stoppen kannst — dein "
        "Körper fragt da nicht lang nach.",
        "de",
    ),
    (
        "Geschmackssinn",
        "Die Zunge erkennt die Geschmacksrichtungen süß, sauer, salzig, bitter und umami. "
        "Geschmack hängt eng mit dem Geruchssinn zusammen, deshalb schmeckt Essen bei "
        "verstopfter Nase deutlich weniger intensiv.",
        "Warum schmeckt Essen komisch, wenn die Nase zu ist?",
        "Weil Schmecken und Riechen eng zusammenarbeiten — die Zunge liefert nur süß, sauer, "
        "salzig, bitter, umami, den Rest liefert die Nase dazu. Fällt die aus, fehlt dem Essen "
        "ein gutes Stück seines Geschmacks.",
        "de",
    ),
    (
        "Schlafdauer",
        "Erwachsene schlafen im Schnitt etwa sieben bis neun Stunden pro Nacht. Im Schlaf "
        "durchläuft der Körper mehrere Phasen, darunter den Traumschlaf, in dem sich die "
        "Augen unter den geschlossenen Lidern schnell bewegen.",
        "Wie viel Schlaf braucht man eigentlich pro Nacht?",
        "Für Erwachsene gelten grob sieben bis neun Stunden als guter Richtwert. Dazwischen "
        "wandert der Körper durch mehrere Phasen, auch durch den Traumschlaf, in dem die Augen "
        "unter den Lidern flackern.",
        "de",
    ),
    (
        "Blutfarbe",
        "Blut ist rot, weil der rote Blutfarbstoff Hämoglobin Eisen enthält, das mit "
        "Sauerstoff reagiert. Ohne Sauerstoff wirkt Blut etwas dunkler, wird aber entgegen "
        "einem verbreiteten Gerücht nie wirklich blau.",
        "Ist unser Blut eigentlich manchmal blau, wenn wenig Sauerstoff drin ist?",
        "Nein, das hält sich nur hartnäckig als Gerücht. Blut ist wegen des Eisens im roten "
        "Blutfarbstoff immer rot — mit wenig Sauerstoff wird es höchstens etwas dunkler.",
        "de",
    ),
    (
        "Sleep duration",
        "Adults typically need about seven to nine hours of sleep per night for the body and "
        "brain to recover. During sleep the body cycles through several stages, including REM "
        "sleep, when most dreaming happens.",
        "How much sleep do people actually need?",
        "Somewhere around seven to nine hours a night, roughly speaking. Your body cycles "
        "through different stages in there, and dreams mostly happen during REM sleep — a "
        "whole quiet nightly journey.",
        "en",
    ),
    (
        "Skeleton",
        "Adult humans have 206 bones, while babies are born with around 300 that gradually "
        "fuse together while growing up. The femur, in the thigh, is the longest and "
        "strongest bone in the body.",
        "How many bones do adults actually have?",
        "About 206, give or take. Babies start out with more, closer to 300, and a bunch of "
        "them just fuse together over time. The longest one of all sits right in your thigh.",
        "en",
    ),
    (
        "Fingerprints",
        "Human fingerprints are unique to each individual, even identical twins have "
        "different patterns. They form before birth and stay the same throughout a person's "
        "life.",
        "Is it true that everyone has a different fingerprint?",
        "Yes, genuinely everyone. Even identical twins, who are alike in almost everything "
        "else, have different patterns there. They form before birth and then stay exactly the "
        "same for life.",
        "en",
    ),
    # --- Musik-Grundlagen ---
    (
        "Oktave",
        "Eine Oktave ist der Abstand zwischen einem Ton und dem nächsten gleichnamigen Ton, "
        "zum Beispiel von einem C zum nächsten C. Der höhere Ton schwingt dabei genau doppelt "
        "so schnell wie der tiefere.",
        "Was ist eigentlich eine Oktave in der Musik?",
        "Der Abstand von einem Ton zum nächsten gleichnamigen, zum Beispiel C zu C. Der höhere "
        "schwingt dabei exakt doppelt so schnell wie der tiefere — Mathematik und Musik, eng "
        "verzahnt.",
        "de",
    ),
    (
        "Klaviertasten",
        "Ein klassisches Klavier hat 88 Tasten, davon 52 weiße und 36 schwarze. Die schwarzen "
        "Tasten füllen die Halbtöne zwischen den weißen Grundtönen.",
        "Wie viele Tasten hat eigentlich ein Klavier?",
        "88 insgesamt, 52 weiße und 36 schwarze. Die schwarzen füllen die Halbtöne zwischen den "
        "weißen auf — ohne sie könnte ein Klavier nur die halbe Farbpalette spielen.",
        "de",
    ),
    (
        "Gitarrensaiten",
        "Eine klassische Gitarre hat sechs Saiten, meist von einem tiefen E über A, D, G, H "
        "bis zum hohen E gestimmt. Je dünner die Saite, desto höher klingt sie in der Regel.",
        "Wie viele Saiten hat eine normale Gitarre?",
        "Sechs Stück, klassisch gestimmt von einem tiefen E bis zum hohen E rauf. Und ganz "
        "einfach gemerkt: je dünner die Saite, desto höher meist der Ton.",
        "de",
    ),
    (
        "Dur und Moll",
        "Dur klingt für die meisten Menschen hell und fröhlich, Moll eher dunkel und "
        "nachdenklich. Der Unterschied liegt oft an einem einzigen Ton der Tonleiter, der "
        "leicht nach oben oder unten verschoben ist.",
        "Was ist eigentlich der Unterschied zwischen Dur und Moll?",
        "Dur klingt für die meisten hell und froh, Moll eher dunkel und nachdenklich. Dabei "
        "hängt der ganze Stimmungswechsel oft nur an einem einzigen verschobenen Ton in der "
        "Tonleiter.",
        "de",
    ),
    (
        "Metronom",
        "Ein Metronom gibt einen gleichmäßigen Takt vor, meist als Klicken oder Ticken, damit "
        "Musizierende im richtigen Tempo bleiben. Die Geschwindigkeit wird in Schlägen pro "
        "Minute angegeben.",
        "Wofür braucht man eigentlich ein Metronom?",
        "Es tickt stur den Takt durch, damit du beim Üben nicht schneller oder langsamer wirst, "
        "ohne es zu merken. Die Geschwindigkeit wird in Schlägen pro Minute gemessen — ein sehr "
        "geduldiger, sehr sturer Übungspartner.",
        "de",
    ),
    (
        "Orchesteraufbau",
        "Ein klassisches Orchester gliedert sich grob in Streicher, Blasinstrumente (Holz und "
        "Blech) und Schlagzeug. Die Streicher, also Geigen, Bratschen, Celli und "
        "Kontrabässe, bilden meist die größte Gruppe.",
        "Wie ist eigentlich ein Orchester aufgebaut?",
        "Grob in drei Familien: Streicher, Blasinstrumente und Schlagzeug. Die Streicher, also "
        "Geigen und ihre größeren Geschwister, sind dabei meist die größte Truppe im ganzen "
        "Orchester.",
        "de",
    ),
    (
        "Beethovens Taubheit",
        "Ludwig van Beethoven komponierte einen großen Teil seiner Werke, obwohl er im Lauf "
        "seines Lebens zunehmend ertaubte. Er soll Klänge teilweise über die Vibrationen des "
        "Klaviers gespürt haben.",
        "Stimmt es, dass Beethoven taub war, als er komponiert hat?",
        "Ja, ein gutes Stück seines Lebens war er zunehmend taub und hat trotzdem "
        "weitergeschrieben. Es heißt, er hat die Vibrationen des Klaviers gespürt, wenn er die "
        "Töne selbst nicht mehr hörte. Beeindruckend, ehrlich gesagt.",
        "de",
    ),
    (
        "Dezibel",
        "Lautstärke wird in Dezibel gemessen. Normales Sprechen liegt bei etwa 60 Dezibel, ein "
        "Rockkonzert kann über 100 Dezibel erreichen. Die Skala ist nicht linear, sondern "
        "logarithmisch, kleine Zahlenunterschiede bedeuten also viel mehr Lautstärke.",
        "Wie laut ist eigentlich ein Rockkonzert im Vergleich zu normalem Reden?",
        "Normales Reden liegt bei etwa 60 Dezibel, ein Rockkonzert kann über 100 knacken. Klingt "
        "nach wenig Unterschied, ist es aber nicht — die Skala wächst logarithmisch, also viel "
        "steiler, als die Zahlen vermuten lassen.",
        "de",
    ),
    (
        "Takt und Rhythmus",
        "Ein Takt gliedert Musik in gleich lange, wiederkehrende Zeitabschnitte, zum Beispiel "
        "vier Schläge im Viervierteltakt. Der Rhythmus ist das Muster, wie Töne innerhalb "
        "dieser Schläge verteilt sind.",
        "Was ist eigentlich der Unterschied zwischen Takt und Rhythmus?",
        "Der Takt ist das feste Raster, zum Beispiel vier gleiche Schläge im Viervierteltakt. "
        "Der Rhythmus ist dann, wie die Töne sich in diesem Raster verteilen — der Takt ist der "
        "Rahmen, der Rhythmus das Bild darin.",
        "de",
    ),
    (
        "A cappella",
        "Musik ohne Instrumentalbegleitung, nur mit menschlichen Stimmen, nennt man a "
        "cappella. Der Begriff stammt aus dem Italienischen und bedeutet ursprünglich „wie in "
        "der Kapelle“.",
        "Was bedeutet eigentlich a cappella?",
        "Musik nur mit Stimmen, ganz ohne Instrumente drunter. Der Ausdruck kommt aus dem "
        "Italienischen und heißt ursprünglich so viel wie „wie in der Kapelle“ — dort wurde "
        "früher eben genauso gesungen.",
        "de",
    ),
    (
        "Absolutes Gehör",
        "Menschen mit absolutem Gehör können einen einzelnen Ton benennen, ohne einen "
        "Vergleichston zu hören. Das ist selten und wird meist durch frühes Musiktraining in "
        "jungen Jahren begünstigt.",
        "Was heißt eigentlich absolutes Gehör?",
        "Manche Menschen können einen einzelnen Ton hören und sofort sagen, welcher es ist, "
        "ganz ohne Vergleichston. Ziemlich selten, und meist entsteht es durch frühes "
        "Musiktraining schon in jungen Jahren.",
        "de",
    ),
    (
        "Piano keys",
        "A standard piano has 88 keys in total, 52 white and 36 black. The black keys fill in "
        "the half-steps between the white notes.",
        "How many keys does a piano actually have?",
        "88 in total, 52 white and 36 black. The black ones fill the half-steps between the "
        "white notes — without them a piano would only have half its colors.",
        "en",
    ),
    (
        "Orchestra families",
        "A classical orchestra is broadly grouped into strings, winds (woodwind and brass), "
        "and percussion. Strings, like violins, violas, cellos, and double basses, are "
        "usually the largest section.",
        "How is an orchestra usually organized?",
        "Broadly into three families: strings, winds, and percussion. Strings, so violins and "
        "their bigger cousins, tend to be the largest group of all up there on stage.",
        "en",
    ),
    (
        "Holz- und Blechbläser",
        "Blasinstrumente teilen sich in Holzblasinstrumente wie Flöte und Klarinette und "
        "Blechblasinstrumente wie Trompete und Posaune. Die Einteilung richtet sich nicht "
        "nach dem Material, sondern danach, wie der Ton erzeugt wird.",
        "Warum heißt eine Flöte Holzblasinstrument, obwohl manche aus Metall sind?",
        "Weil die Einteilung nicht am Material hängt, sondern daran, wie der Ton entsteht. Bei "
        "Holzbläsern wie der Flöte schwingt Luft an einer Kante oder einem Rohrblatt, bei "
        "Blechbläsern wie der Trompete sind es die Lippen selbst.",
        "de",
    ),
    # --- Sprache/Wörter ---
    (
        "Alphabet",
        "Das deutsche Alphabet baut auf den 26 lateinischen Buchstaben auf, dazu kommen die "
        "Umlaute ä, ö, ü und das ß, je nachdem wie man zählt. Das lateinische Alphabet selbst "
        "geht über die Griechen letztlich auf die Phönizier zurück.",
        "Wie viele Buchstaben hat eigentlich das deutsche Alphabet?",
        "Die Basis sind 26 wie im Lateinischen, dazu kommen noch die Umlaute und das scharfe S, "
        "je nachdem wie man zählt. Die Wurzeln des ganzen Alphabets reichen übrigens bis zu den "
        "Phöniziern zurück.",
        "de",
    ),
    (
        "Palindrom",
        "Ein Palindrom ist ein Wort oder Satz, der vorwärts wie rückwärts gelesen gleich "
        "bleibt, zum Beispiel „Otto“ oder „Anna“. Auch ganze Sätze können Palindrome sein, "
        "wenn man Leerzeichen ignoriert.",
        "Was ist eigentlich ein Palindrom?",
        "Ein Wort, das vorwärts und rückwärts gelesen gleich bleibt, wie „Otto“ oder „Anna“. Es "
        "gibt sogar ganze Sätze, die das schaffen — kleine Sprachkunststücke, die ich ziemlich "
        "charmant finde.",
        "de",
    ),
    (
        "Synonym und Antonym",
        "Synonyme sind Wörter mit ähnlicher Bedeutung, zum Beispiel „schön“ und „hübsch“. "
        "Antonyme sind Gegensatzpaare, wie „groß“ und „klein“.",
        "Was ist eigentlich der Unterschied zwischen Synonym und Antonym?",
        "Synonyme bedeuten fast dasselbe, wie „schön“ und „hübsch“. Antonyme sind das Gegenteil "
        "voneinander, wie „groß“ und „klein“ — zwei Wörter, zwei völlig verschiedene "
        "Richtungen.",
        "de",
    ),
    (
        "Meistgesprochene Sprache",
        "Nach Zahl der Sprecher weltweit gehören Englisch, Mandarin-Chinesisch, Hindi und "
        "Spanisch zu den meistgesprochenen Sprachen. Zählt man nur Muttersprachler, liegt "
        "Mandarin meist vorn.",
        "Welche Sprache wird eigentlich weltweit am meisten gesprochen?",
        "Kommt drauf an, wie man zählt. Bei Muttersprachlern liegt oft Mandarin vorn, zählt man "
        "alle Sprecher zusammen, mischen Englisch, Hindi und Spanisch ganz oben mit.",
        "de",
    ),
    (
        "Anzahl Sprachen weltweit",
        "Weltweit gibt es schätzungsweise etwa 7000 Sprachen, viele davon werden nur noch von "
        "wenigen Menschen gesprochen und gelten als gefährdet. Eine Sprache kann aussterben, "
        "wenn ihre letzten Sprecher versterben.",
        "Wie viele Sprachen gibt es eigentlich auf der Welt?",
        "Schätzungsweise um die 7000, das schwankt je nach Zählung. Traurig dabei: viele davon "
        "werden nur noch von wenigen Menschen gesprochen und drohen still zu verschwinden.",
        "de",
    ),
    (
        "Gebärdensprache",
        "Gebärdensprachen sind eigenständige Sprachen mit eigener Grammatik, kein bloßes "
        "Abbild der gesprochenen Sprache. Es gibt viele verschiedene Gebärdensprachen "
        "weltweit, etwa Deutsche Gebärdensprache und American Sign Language.",
        "Ist Gebärdensprache eigentlich überall auf der Welt gleich?",
        "Nein, überhaupt nicht. Es gibt viele eigenständige Gebärdensprachen mit eigener "
        "Grammatik, ganz verschieden von Land zu Land. Deutsche Gebärdensprache und "
        "amerikanische Gebärdensprache sind zum Beispiel zwei völlig eigene Sprachen.",
        "de",
    ),
    (
        "Etymologie",
        "Etymologie ist die Wissenschaft von der Herkunft und Entwicklung von Wörtern. Viele "
        "deutsche Wörter stammen aus dem Lateinischen oder Griechischen oder haben sich aus "
        "älteren germanischen Formen entwickelt.",
        "Was macht man eigentlich in der Etymologie?",
        "Man geht Wörtern auf den Grund, woher sie eigentlich kommen. Viele deutsche Wörter "
        "haben lateinische oder griechische Wurzeln, andere stammen aus ganz alten germanischen "
        "Formen — Wörter haben quasi eigene kleine Biografien.",
        "de",
    ),
    (
        "Lautmalerei",
        "Lautmalerische Wörter, sogenannte Onomatopoetika, ahmen Geräusche nach, wie „miau“, "
        "„platsch“ oder „zisch“. Sie klingen dabei oft ähnlich wie das, was sie beschreiben.",
        "Was sind eigentlich lautmalerische Wörter?",
        "Wörter, die ein Geräusch quasi nachmachen, wie „platsch“ oder „zisch“. Die klingen fast "
        "wie das, was sie beschreiben — die Sprache schummelt sich da ein bisschen näher an die "
        "Wirklichkeit ran.",
        "de",
    ),
    (
        "Dialekt",
        "Ein Dialekt ist eine regionale Sprachvariante innerhalb einer Sprache, mit eigenem "
        "Wortschatz, eigener Aussprache und manchmal eigener Grammatik. In Deutschland gibt "
        "es sehr viele Dialekte, die sich teils stark unterscheiden.",
        "Was ist eigentlich genau ein Dialekt?",
        "Eine regionale Spielart einer Sprache, mit eigenem Wortschatz, eigener Aussprache, "
        "manchmal sogar eigener Grammatik. In Deutschland gibt es davon erstaunlich viele, "
        "manche verstehen sich untereinander kaum.",
        "de",
    ),
    (
        "Redewendungen",
        "Redewendungen sind feste Ausdrücke, deren Bedeutung sich nicht wörtlich aus den "
        "einzelnen Wörtern ergibt, wie „die Katze im Sack kaufen“. Sie sind oft historisch "
        "gewachsen und unterscheiden sich von Sprache zu Sprache.",
        "Warum ergeben manche Redewendungen wörtlich gar keinen Sinn?",
        "Weil sie eben nicht wörtlich gemeint sind — „die Katze im Sack kaufen“ hat mit echten "
        "Katzen nichts zu tun. Solche Wendungen sind über Jahrhunderte gewachsen, jede Sprache "
        "hat da ihre eigenen kleinen Rätsel.",
        "de",
    ),
    (
        "Muttersprache",
        "Die Muttersprache ist die Sprache, die ein Mensch als Erstes lernt, meist von den "
        "Eltern oder der nächsten Umgebung. Viele Menschen wachsen mehrsprachig auf und haben "
        "dadurch mehr als eine Muttersprache.",
        "Kann man eigentlich mehr als eine Muttersprache haben?",
        "Ja, durchaus. Wächst jemand von klein auf mit zwei Sprachen gleichzeitig auf, zählen "
        "oft beide als Muttersprache. Die Umgebung entscheidet meistens mit, welche Sprache "
        "sich als Erstes festsetzt.",
        "de",
    ),
    (
        "Palindrome",
        "A palindrome is a word or sentence that reads the same forwards and backwards, like "
        "\"level\" or \"racecar\". Entire sentences can be palindromes too, if spaces are "
        "ignored.",
        "What exactly is a palindrome?",
        "A word that reads the same forwards and backwards, like \"level\" or \"racecar\". Some "
        "whole sentences pull that off too — small language tricks I find oddly satisfying.",
        "en",
    ),
    (
        "Number of languages",
        "There are an estimated 7000 languages spoken worldwide, and many are spoken by only "
        "a small number of people, putting them at risk of disappearing. A language can die "
        "out when its last speakers pass away.",
        "How many languages exist in the world?",
        "Roughly 7000, give or take, depending on how you count. A lot of those are only spoken "
        "by a handful of people, which is quietly sad — some languages fade out that way.",
        "en",
    ),
    (
        "Onomatopoeia",
        "Onomatopoetic words imitate the sound they describe, like \"buzz\", \"splash\", or "
        "\"hiss\". Many languages have their own versions, and they often sound quite "
        "different from language to language.",
        "What are onomatopoeic words exactly?",
        "Words that basically imitate a sound, like \"buzz\" or \"splash\". Every language has "
        "its own versions, and they can sound surprisingly different from one another — "
        "language trying to mimic the real world.",
        "en",
    ),
    # --- Spiele/Sport-Regeln ---
    (
        "Schachbrett",
        "Ein Schachbrett hat 64 Felder, abwechselnd hell und dunkel gefärbt. Jede Partei "
        "startet mit 16 Figuren: acht Bauern, zwei Türme, zwei Springer, zwei Läufer, eine "
        "Dame und einen König.",
        "Wie viele Felder hat eigentlich ein Schachbrett?",
        "64 Stück, hell und dunkel im Wechsel. Jede Seite tritt mit 16 Figuren an, Bauern, "
        "Türme, Springer, Läufer, eine Dame und ein König — ein kleines Heer für ein sehr "
        "stilles Spiel.",
        "de",
    ),
    (
        "Fußball-Spieleranzahl",
        "Beim Fußball stehen pro Mannschaft elf Spieler auf dem Platz, einer davon der "
        "Torwart. Ein Spiel dauert regulär zweimal 45 Minuten mit einer Halbzeitpause "
        "dazwischen.",
        "Wie viele Spieler hat eigentlich eine Fußballmannschaft auf dem Platz?",
        "Elf pro Team, einer davon steht im Tor. Gespielt wird zweimal 45 Minuten mit einer "
        "Pause dazwischen — genug Zeit für ziemlich viel Drama auf kleinem Raum.",
        "de",
    ),
    (
        "Abseits",
        "Abseits im Fußball liegt vor, wenn ein Angreifer beim Zuspiel näher am gegnerischen "
        "Tor steht als der vorletzte Verteidiger, meist wird der Torwart als letzter "
        "mitgezählt. Die Regel soll verhindern, dass Spieler einfach vorm Tor lauern.",
        "Kannst du mir Abseits im Fußball kurz erklären?",
        "Ein Angreifer steht abseits, wenn er beim Zuspiel näher am gegnerischen Tor ist als der "
        "vorletzte Verteidiger. Die Regel verhindert, dass jemand einfach vorm Tor stehen "
        "bleibt und auf den Ball wartet.",
        "de",
    ),
    (
        "Tennis-Zählweise",
        "Beim Tennis zählt man Punkte innerhalb eines Spiels als 15, 30, 40 und dann "
        "Spielgewinn, bei Gleichstand heißt es Einstand. Mehrere gewonnene Spiele ergeben "
        "einen Satz, mehrere Sätze ein Match.",
        "Warum zählt man beim Tennis eigentlich 15, 30, 40?",
        "Eine alte, etwas eigenwillige Zählweise, die sich einfach gehalten hat. Nach 15, 30, 40 "
        "ist das Spiel gewonnen, bei Gleichstand heißt es Einstand. Mehrere Spiele ergeben "
        "einen Satz, mehrere Sätze das ganze Match.",
        "de",
    ),
    (
        "Volleyball",
        "Beim Volleyball stehen sechs Spieler pro Team auf dem Feld. Der Ball darf pro "
        "Ballwechsel maximal dreimal berührt werden, bevor er über das Netz muss.",
        "Wie viele Spieler hat eine Volleyballmannschaft auf dem Feld?",
        "Sechs pro Team. Und der Ball darf höchstens dreimal berührt werden, bevor er wieder "
        "rüber übers Netz muss — Teamarbeit auf sehr engem Zeitfenster.",
        "de",
    ),
    (
        "Basketball-Punkte",
        "Beim Basketball zählt ein Korb aus dem normalen Feld zwei Punkte, ein Korb von "
        "jenseits der Dreipunktlinie drei Punkte, ein Freiwurf einen Punkt. Der Korb hängt "
        "bei einer weltweit einheitlichen Höhe von 3,05 Metern über dem Boden.",
        "Wie viele Punkte gibt es eigentlich für einen Korb im Basketball?",
        "Normal zwei Punkte, von jenseits der Dreipunktlinie gibt's drei, ein Freiwurf zählt "
        "einen. Und der Korb selbst hängt immer exakt gleich hoch, bei 3,05 Metern — ganz "
        "gleich, wie groß die Spieler sind.",
        "de",
    ),
    (
        "Olympische Ringe",
        "Die fünf olympischen Ringe stehen für die fünf teilnehmenden Kontinente und wurden "
        "vom Mitbegründer der modernen Olympischen Spiele, Pierre de Coubertin, entworfen. "
        "Sie sind ineinander verschlungen, als Zeichen der Verbundenheit im olympischen "
        "Gedanken.",
        "Wofür stehen eigentlich die olympischen Ringe?",
        "Für die fünf teilnehmenden Kontinente, entworfen vom Mitbegründer der modernen Spiele. "
        "Ineinander verschlungen, als kleines Zeichen dafür, wie eng das alles zusammenhängt. "
        "Ein hübsches Symbol für ein sehr großes Fest.",
        "de",
    ),
    (
        "Kartenspiele",
        "Ein klassisches Skatblatt hat 32 Karten, ein französisches Blatt, wie es beim Poker "
        "verwendet wird, hat 52 Karten in vier Farben. Jede Farbe hat dabei die gleiche "
        "Anzahl an Karten.",
        "Wie viele Karten hat eigentlich ein normales Kartenspiel?",
        "Kommt aufs Blatt an. Skat spielt man mit 32 Karten, das französische Blatt für Poker "
        "hat 52, verteilt auf vier gleich große Farben.",
        "de",
    ),
    (
        "Würfel",
        "Ein klassischer Spielwürfel hat sechs Seiten mit den Augenzahlen eins bis sechs. Bei "
        "den meisten Würfeln ergeben gegenüberliegende Seiten zusammen immer sieben.",
        "Stimmt es, dass sich gegenüberliegende Würfelseiten immer zu sieben addieren?",
        "Bei den meisten klassischen Würfeln ja. Eins liegt der Sechs gegenüber, Zwei der Fünf, "
        "Drei der Vier — addiert ergibt das immer sieben. Ein kleines, ordentliches System.",
        "de",
    ),
    (
        "Marathonstrecke",
        "Ein Marathon ist genau 42,195 Kilometer lang. Die Distanz geht auf die Legende eines "
        "Läufers zurück, der die Strecke von Marathon nach Athen gelaufen sein soll, um eine "
        "Nachricht zu überbringen.",
        "Wie lang ist eigentlich ein Marathon genau?",
        "Ganz genau 42,195 Kilometer. Die Zahl geht auf die alte Geschichte eines Läufers "
        "zurück, der von Marathon nach Athen gerannt sein soll, um eine Nachricht zu "
        "überbringen.",
        "de",
    ),
    (
        "Boxrunden",
        "Profi-Boxkämpfe dauern je nach Verband und Gewichtsklasse meist zwölf Runden zu je "
        "drei Minuten, mit einer kurzen Pause dazwischen. Amateurkämpfe sind in der Regel "
        "deutlich kürzer angesetzt.",
        "Wie viele Runden boxt man eigentlich bei einem Profikampf?",
        "Meist zwölf Runden zu je drei Minuten, mit kurzen Pausen dazwischen. Amateurkämpfe "
        "sind in der Regel deutlich kürzer angesetzt.",
        "de",
    ),
    (
        "Chess board",
        "A chessboard has 64 squares in alternating light and dark colors. Each side starts "
        "with 16 pieces: eight pawns, two rooks, two knights, two bishops, one queen, and one "
        "king.",
        "How many squares does a chessboard have?",
        "64, light and dark alternating. Each side starts out with 16 pieces — pawns, rooks, "
        "knights, bishops, one queen, one king. A whole quiet little army.",
        "en",
    ),
    (
        "Marathon distance",
        "A marathon is exactly 42.195 kilometers long. The distance is linked to the legend "
        "of a runner who supposedly ran from Marathon to Athens to deliver a message.",
        "How long is a marathon exactly?",
        "Exactly 42.195 kilometers. The number traces back to an old story about a runner who "
        "supposedly ran from Marathon to Athens just to deliver a message.",
        "en",
    ),
]
