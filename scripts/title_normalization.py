"""Title keys shared by both asset builders; mirror BeatIndex/SteamTitleMatcher."""
import re
import unicodedata


def normalize(value):
    # Only standalone/suffix (TM)/(R)/(C); (R)Evolution and Drak(c)ula are title words.
    value = re.sub(r"\((?:tm|r|c)\)(?![^\W_])", "", value, flags=re.IGNORECASE)
    value = unicodedata.normalize("NFKD", value.translate(str.maketrans("", "", "™®©")))
    value = "".join(c for c in value if not unicodedata.category(c).startswith("M")).lower()
    return " ".join("".join(c if unicodedata.category(c)[0] in "LN" else " " for c in value).split())


def relaxed_key(title):
    title = re.sub(r",\s*(the|an|a)\s*$", "", title, flags=re.IGNORECASE)
    title = re.sub("['’‘ʼ]", "", title).replace("&", " and ")
    words = re.sub(r"^(the|an|a) ", "", normalize(title))
    key = words.replace(" ", "")
    return key if len(key) >= 3 else ""
