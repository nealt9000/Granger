"""Adult-content class rules (roadmap, "Carried forward from EX", Neal 2026-11-18).

Granger doesn't volunteer it, and never hides what someone searches for or opens. Two kinds of flag per Wikidata item:
TOPIC (1), kept off every screen that picks by rank; PICTURE (2), the plate stays without its picture.

Items are wdfilter.py records (tools/timeindex): q, t, P31, P279, P106, P136. Classes are its classes.jsonl (q, p279).
"""
import collections
import json

TOPIC, PICTURE = 1, 2

# The topic itself is sexual. Genre (P136) counts only through HARD.
TOPIC_SEEDS = {
    "Q291": "pornography", "Q185529": "pornographic film", "Q172067": "hentai", "Q1046788": "eroge",
    "Q7230210": "pornographic video game", "Q3244962": "pornographic magazine", "Q644437": "snuff film",
    "Q8394": "sex position", "Q178059": "paraphilia", "Q207791": "sexual fetishism", "Q190845": "BDSM",
    "Q10816": "sex toy", "Q8396": "masturbation", "Q5873": "sexual intercourse", "Q608": "human sexual behavior",
}
HARD_GENRES = ("Q291", "Q185529", "Q172067", "Q1046788", "Q7230210", "Q3244962", "Q644437")
# Sexual behaviour and violence: a concept is a TOPIC; an event (an instance of one) only loses its picture.
BEHAVIOUR_SEED = "Q608"
# People are judged by occupation alone.
TOPIC_JOBS = {"Q488111": "pornographic film actor", "Q17456089": "pornographic film director"}
HUMAN = "Q5"

# The topic is fine, its picture may not be. Neal's Top review kept almost none of the gore classes' pictures, so
# outside the reviewed lists only SEXUAL_PICTURE seeds apply (and an STI only when it's genital: GENITAL_WORDS).
PICTURE_SEEDS = {
    "Q4620674": "sex organ", "Q9103": "breast", "Q10791": "nudity", "Q12198": "sexually transmitted infection",
    "Q40446": "nude", "Q105993773": "nude", "Q1168168": "erotic art", "Q1361392": "erotic photography",
    "Q421966": "nude photography", "Q949302": "skin disease", "Q486775": "lynching", "Q132781": "torture",
    "Q56826919": "torture", "Q767568": "mutilation", "Q208373": "female genital mutilation", "Q83345": "circumcision",
    "Q48422": "corpse", "Q105525662": "human remains", "Q131599095": "human remains", "Q204933": "decapitation",
    "Q15169167": "execution method", "Q3199915": "massacre", "Q41397": "genocide", "Q135010": "war crime",
    "Q4817637": "atrocity", "Q193078": "injury", "Q184753": "wound", "Q1538137": "gore film", "Q44595": "cannibalism",
    "Q36633": "prostitution",
}
NUDE_GENRES = ("Q40446", "Q105993773")
SEXUAL_PICTURE = {"Q4620674", "Q9103", "Q10791"}
STI = "Q12198"
GENITAL_WORDS = ("genital", "venere", "génital")
# By hand: history, not a concept (Neal, 2026-11-18).
EVENT_BY_HAND = {"Q713540"}  # comfort women


def load_children(classes_path):
    children = collections.defaultdict(list)
    with open(classes_path) as f:
        for line in f:
            d = json.loads(line)
            for p in d.get("p279") or ():
                children[p].append(d["q"])
    return children


def closure(children, seeds):
    """Every class under the seeds -> the seed it came from."""
    out, stack = {}, [(s, s) for s in seeds]
    while stack:
        q, seed = stack.pop()
        if q not in out:
            out[q] = seed
            stack.extend((c, seed) for c in children.get(q, ()))
    return out


class Rules:
    def __init__(self, children):
        self.topic = closure(children, TOPIC_SEEDS)
        self.picture = {q: s for q, s in closure(children, PICTURE_SEEDS).items() if q not in self.topic}
        self.jobs = closure(children, TOPIC_JOBS)
        self.hard = closure(children, HARD_GENRES)
        self.nude = closure(children, NUDE_GENRES)

    def classify(self, it):
        """(kind, seed, why) for an item, or None. kind is TOPIC or PICTURE; seed is the seed class the rule hit."""
        q = it["q"]
        if HUMAN in it.get("P31", ()):
            for v in it.get("P106", ()):
                if v in self.jobs:
                    return TOPIC, self.jobs[v], f"P106 {v}"
            return None
        hits = []
        for prop in ("self", "P31", "P279", "P136"):
            for v in ([q] if prop == "self" else it.get(prop, ())):
                if prop == "P136" and v not in self.hard and v not in self.nude:
                    continue
                if v in self.topic:
                    seed = self.topic[v]
                    event = q in EVENT_BY_HAND or (prop == "P31" and seed == BEHAVIOUR_SEED and q not in self.topic)
                    hits.append((PICTURE if event else TOPIC, seed, f"{prop} {v}"))
                elif v in self.picture:
                    hits.append((PICTURE, self.picture[v], f"{prop} {v}"))
        if not hits:
            return None
        return min(hits, key=lambda h: h[0])


def picture_outside_review(seed, title):
    """Outside the reviewed Top lists, only sexual anatomy loses its picture (Neal, 2026-11-18)."""
    if seed in SEXUAL_PICTURE:
        return True
    return seed == STI and any(w in title.lower() for w in GENITAL_WORDS)
