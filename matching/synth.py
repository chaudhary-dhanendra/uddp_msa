"""Seeded synthetic resident generator (no real personal data).

python -m matching.synth --size 10000 --seed 42 --out <dir>

``size`` is the total number of source rows. Rows are written in the sample-data
CSV formats of the three sample states; ``ground_truth.csv`` maps every row to the
true person and household it was generated from.
"""
import argparse
import csv
import datetime as dt
import random
from pathlib import Path

FIRST = {
    "M": ["Ram", "Mohan", "Rajesh", "Suresh", "Arjun", "Vikram", "Anil", "Sanjay", "Manoj", "Deepak", "Ravi", "Amit",
          "Krishna", "Gopal", "Harish", "Naveen", "Prakash", "Rakesh", "Shyam", "Venkatesh", "Srinivas", "Ramesh",
          "Mohammad", "Imran", "Gurpreet", "Harpreet", "Biswajit", "Subrata", "Mahesh", "Dinesh"],
    "F": ["Sita", "Anita", "Sunita", "Geeta", "Radha", "Lakshmi", "Priya", "Neha", "Kavita", "Meena", "Pooja", "Savita",
          "Rekha", "Usha", "Kamala", "Sarita", "Laxmi", "Shanti", "Parvati", "Durga", "Fatima", "Ayesha", "Sunanda",
          "Jyoti", "Manju", "Rani", "Sushila", "Padma", "Gita", "Mamta"],
}
LAST = ["Singh", "Kumar", "Sharma", "Verma", "Yadav", "Devi", "Patel", "Gupta", "Mishra", "Reddy", "Nair", "Iyer",
        "Das", "Ghosh", "Banerjee", "Chatterjee", "Khan", "Ahmed", "Kaur", "Gill", "Joshi", "Patil", "Deshmukh",
        "Rao", "Naidu", "Pillai", "Mondal", "Sahu", "Mahto", "Paswan"]
# transliteration / spelling variants
VARIANTS = {
    "Ram": ["Raam", "Rama"], "Mohan": ["Mohon"], "Rajesh": ["Rajeesh", "Rajes"], "Suresh": ["Sureish"],
    "Vikram": ["Bikram", "Vikrum"], "Krishna": ["Krishnan", "Kishna"], "Venkatesh": ["Venkatesan"],
    "Mohammad": ["Muhammad", "Mohd", "Md"], "Sita": ["Seeta", "Sitha"], "Anita": ["Aneeta", "Anitha"],
    "Sunita": ["Suneeta", "Sunitha"], "Geeta": ["Gita", "Githa"], "Lakshmi": ["Laxmi", "Lakshmy"],
    "Priya": ["Priyaa", "Preeya"], "Kavita": ["Kavitha"], "Laxmi": ["Lakshmi"], "Fatima": ["Fatma"],
    "Singh": ["Sing", "Sinha", "Simh"], "Kumar": ["Kumaar", "Kr"], "Sharma": ["Sarma", "Sharmaa"],
    "Verma": ["Varma", "Barma"], "Yadav": ["Yadava", "Jadav"], "Devi": ["Debi", "Dewi"], "Patel": ["Pattel"],
    "Gupta": ["Guptha"], "Mishra": ["Misra", "Mishraa"], "Reddy": ["Reddi", "Redy"], "Das": ["Dass", "Dash"],
    "Ghosh": ["Ghose", "Gosh"], "Khan": ["Khaan"], "Kaur": ["Kour"], "Banerjee": ["Bandyopadhyay", "Banarjee"],
    "Chatterjee": ["Chattopadhyay", "Chaterjee"],
}
HONORIFICS = ["Shri", "Smt", "Mr", "Mrs", "Dr", "Sri"]
DISTRICTS = [("101", "Patna"), ("102", "Gaya"), ("103", "Nalanda"), ("104", "Ranchi"), ("105", "Pune"),
             ("106", "Chennai"), ("107", "Kolkata"), ("108", "Jaipur"), ("109", "Lucknow"), ("110", "Amritsar")]
PENSION_TYPES = ["VRIDHA", "VIDHWA", "DIVYANG"]
SCHEMES = ["OAP", "WIDOW", "DISAB"]
BLOOD = ["A+", "B+", "O+", "AB+", "A-", "B-", "O-"]
STATES = ("state-1", "state-2", "state-3")
HEADERS = {
    "state-1": "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,card_issued_on,"
               "monthly_grain_kg,last_lifted_on,last_lifted_kg",
    "state-2": "ben_name,birth_dt,gender,district_name,family_ref,phone,pension_type,pension_order_no,"
               "pension_status,sanctioned_on,monthly_amount,last_paid_on,last_paid_amount",
    "state-3": "person_name,dob,gender,district,family_id,mobile,blood_group,disability_pct,welfare_scheme,"
               "welfare_id,welfare_status,welfare_since,monthly_amount,last_paid_on,last_paid_amount",
}
FILES = {"state-1": "pds.csv", "state-2": "pension.csv", "state-3": "beneficiaries.csv"}


class Person:
    __slots__ = ("pid", "hid", "first", "last", "sex", "dob", "district", "mobile", "ration", "family_ref")


def _digits(rng, n):
    return "".join(rng.choice("0123456789") for _ in range(n))


def make_households(rng, count):
    """Yield households as lists of Person; members share surname, district and usually a mobile."""
    for h in range(count):
        hid = f"HH{h:07d}"
        last = rng.choice(LAST)
        district = rng.choice(DISTRICTS)
        mobile = "9" + _digits(rng, 9)
        size = rng.choices([1, 2, 3, 4, 5], [2, 4, 4, 3, 1])[0]
        people = []
        for m in range(size):
            p = Person()
            p.pid = f"P{h:07d}-{m}"
            p.hid = hid
            p.sex = rng.choice("MF") if m else "M"
            p.first = rng.choice(FIRST[p.sex])
            p.last = "Devi" if (p.sex == "F" and rng.random() < 0.3) else last
            age = rng.randint(30, 80) if m < 2 else rng.randint(1, 40)
            p.dob = dt.date(2026 - age, rng.randint(1, 12), rng.randint(1, 28))
            p.district = district
            p.mobile = mobile if rng.random() < 0.75 else "9" + _digits(rng, 9)
            p.ration = f"R{h:07d}"
            p.family_ref = f"F{h:07d}"
            people.append(p)
        yield people


def _variant_word(rng, word):
    return rng.choice(VARIANTS[word]) if word in VARIANTS and rng.random() < 0.6 else word


def render_name(rng, p):
    """Return the name as one department might write it, with realistic noise."""
    first, last = _variant_word(rng, p.first), _variant_word(rng, p.last)
    r = rng.random()
    if r < 0.10:
        first = first[0] + "."
    elif r < 0.14:
        first = first[0]
    name = f"{last} {first}" if rng.random() < 0.08 else f"{first} {last}"
    if rng.random() < 0.06:
        name = f"{rng.choice(HONORIFICS)} {name}"
    if rng.random() < 0.04 and len(name) > 4:  # keyboard typo: drop or swap a character
        i = rng.randint(1, len(name) - 3)
        name = name[:i] + name[i + 1:] if rng.random() < 0.5 else name[:i] + name[i + 1] + name[i] + name[i + 2:]
    return name


def noisy_dob(rng, d):
    r = rng.random()
    if r < 0.04 and d.day <= 12:
        return dt.date(d.year, d.day, d.month)  # day-month swap
    if r < 0.08:
        return dt.date(d.year + rng.choice([-1, 1]), d.month, d.day)  # year typo
    if r < 0.10:
        return dt.date(d.year, d.month, min(28, max(1, d.day + rng.choice([-1, 1]))))
    return d


def _row(state, rng, p, n):
    name = render_name(rng, p)
    dob = noisy_dob(rng, p.dob)
    mobile = p.mobile if rng.random() > 0.05 else ("" if rng.random() < 0.5 else "9" + _digits(rng, 9))
    code, dname = p.district
    amount = rng.choice([500, 1000, 1200, 1500])
    if state == "state-1":
        return [name, dob.strftime("%d-%m-%Y"), p.sex, code, p.ration, mobile, rng.choice(["PHH", "AAY", "NPHH"]),
                "A", "01-04-2015", 25, "05-10-2026", 25]
    if state == "state-2":
        return [name, dob.strftime("%Y%m%d"), "1" if p.sex == "M" else "2", dname, p.family_ref, mobile,
                rng.choice(PENSION_TYPES), f"PPO-{n:08d}", 1, "20250401", amount, "20261001", amount]
    welfare = (rng.choice(SCHEMES), f"WEL-{n:08d}", "ACTIVE", "2025-06-01", amount, "2026-10-03", amount) \
        if rng.random() < 0.4 else ("",) * 7
    return [name, dob.strftime("%Y-%m-%d"), p.sex, dname, p.family_ref, mobile, rng.choice(BLOOD),
            rng.choice([0, 0, 0, 20, 40]), *welfare]


def generate(size, seed, out):
    rng = random.Random(seed)
    out = Path(out)
    writers, handles = {}, []
    for s in STATES:
        (out / s).mkdir(parents=True, exist_ok=True)
        f = open(out / s / FILES[s], "w", newline="", encoding="utf-8")
        handles.append(f)
        w = csv.writer(f)
        w.writerow(HEADERS[s].split(","))
        writers[s] = w
    truth = open(out / "ground_truth.csv", "w", newline="", encoding="utf-8")
    handles.append(truth)
    tw = csv.writer(truth)
    tw.writerow(["state", "row_number", "true_person_id", "household_id"])
    counts = {s: 0 for s in STATES}
    total = persons = 0
    for household in make_households(rng, size):
        for p in household:
            if total >= size:
                break
            persons += 1
            k = rng.choices([1, 2, 3], [5, 4, 2])[0]  # departments holding this person
            for s in rng.sample(STATES, k):
                if total >= size:
                    break
                counts[s] += 1
                total += 1
                writers[s].writerow(_row(s, rng, p, total))
                tw.writerow([s, counts[s], p.pid, p.hid])
        if total >= size:
            break
    for f in handles:
        f.close()
    return {"rows": total, "persons": persons, **counts}


def main(argv=None):
    ap = argparse.ArgumentParser(description="Seeded synthetic resident generator")
    ap.add_argument("--size", type=int, default=10000)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    print(generate(a.size, a.seed, a.out))


if __name__ == "__main__":
    main()


def load_records(out):
    """Generated rows as the normalised frame matching works on, plus the true person for evaluation."""
    import pandas as pd
    from .run import normalise_mobile, normalise_name
    out = Path(out)
    district = {name: code for code, name in DISTRICTS}
    rows = []
    for s in STATES:
        with open(out / s / FILES[s], encoding="utf-8") as f:
            for number, r in enumerate(csv.DictReader(f), start=1):
                v = list(r.values())
                raw = v[1]
                if s == "state-1":
                    dob = f"{raw[6:10]}-{raw[3:5]}-{raw[0:2]}"
                elif s == "state-2":
                    dob = f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
                else:
                    dob = raw
                gender = {"1": "M", "2": "F"}.get(v[2], v[2])
                rows.append({"canonical_record_id": f"{s}:{number}", "name": normalise_name(v[0]),
                             "date_of_birth": dob, "gender": gender, "mobile": normalise_mobile(v[5]),
                             "district_lgd": v[3] if s == "state-1" else district.get(v[3], v[3]),
                             "national_id": None, "relation_name": None})
    df = pd.DataFrame(rows)
    with open(out / "ground_truth.csv", encoding="utf-8") as f:
        truth = {f"{t['state']}:{t['row_number']}": t["true_person_id"] for t in csv.DictReader(f)}
    df["true_person_id"] = df["canonical_record_id"].map(truth)
    return df
