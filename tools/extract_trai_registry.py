"""Build the compact offline bank/header registry from TRAI's published workbook.

Usage:
  python tools/extract_trai_registry.py work/trai_sms_headers_2020.xlsx \
      app/src/main/assets/bank_sender_registry.json
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import openpyxl


# Display name -> exact Principal Entity names as printed in the TRAI workbook.
# Old/merged entities can be added as aliases without changing the on-device schema.
BANKS: dict[str, tuple[str, ...]] = {
    "State Bank of India": ("STATE BANK OF INDIA",),
    "HDFC Bank": ("HDFC BANK LIMITED",),
    "ICICI Bank": ("ICICI BANK LIMITED",),
    "Axis Bank": ("AXIS BANK LIMITED",),
    "Kotak Mahindra Bank": ("Kotak Mahindra Bank Ltd",),
    "Punjab National Bank": ("PUNJAB NATIONAL BANK",),
    "Bank of Baroda": ("BANK OF BARODA",),
    "Canara Bank": ("Canara Bank",),
    "Union Bank of India": ("UNION BANK OF INDIA",),
    "Bank of India": ("BANK OF INDIA",),
    "Indian Bank": ("Indian Bank",),
    "Central Bank of India": ("Central Bank of India",),
    "UCO Bank": ("UCO BANK",),
    "Bank of Maharashtra": ("BANK OF MAHARASHTRA",),
    "Punjab & Sind Bank": ("Punjab And Sind Bank",),
    "Yes Bank": ("Yes Bank Ltd",),
    "IndusInd Bank": ("INDUSIND BANK LIMITED",),
    "IDFC FIRST Bank": ("IDFC FIRST BANK LIMITED",),
    "Federal Bank": ("FEDERAL BANK",),
    "South Indian Bank": ("THE SOUTH INDIAN BANK LIMITED",),
    "Karnataka Bank": ("The KARNATAKA BANK LIMITED",),
    "Karur Vysya Bank": ("The Karur Vysya Bank Limited",),
    "City Union Bank": ("City Union Bank",),
    "Tamilnad Mercantile Bank": ("TAMILNAD MERCANTILE BANK LIMITED",),
    "Jammu & Kashmir Bank": ("THE JAMMU AND KASHMIR BANK LIMITED",),
    "DCB Bank": ("DCB Bank Limited",),
    "RBL Bank": ("RBL BANK LIMITED",),
    "Bandhan Bank": ("BANDHAN BANK LIMITED",),
    "CSB Bank": ("CSB Bank Limited",),
    "DBS Bank India": ("DBS BANK INDIA LIMITED",),
    "Standard Chartered Bank": ("STANDARD CHARTERED BANK",),
    "HSBC India": ("The Hongkong & Shanghai Banking Corporation Limited",),
    "Deutsche Bank India": ("DEUTSCHE BANK AG",),
    "AU Small Finance Bank": ("AU SMALL FINANCE BANK LIMITED",),
    "Equitas Small Finance Bank": ("EQUITAS SMALL FINANCE BANK LIMITED",),
    "Ujjivan Small Finance Bank": ("UJJIVAN SMALL FINANCE BANK LIMITED",),
    "Jana Small Finance Bank": ("JANA SMALL FINANCE BANK LTD",),
    "Suryoday Small Finance Bank": ("Suryoday Small Finance Bank Limited",),
    "ESAF Small Finance Bank": ("ESAF SMALL FINANCE BANK LIMITED",),
    "Utkarsh Small Finance Bank": ("Utkarsh Small Finance Bank Ltd",),
    "Capital Small Finance Bank": ("Capital Small Finance Bank Limited",),
    "Airtel Payments Bank": ("Airtel Payments Bank Limited",),
    "India Post Payments Bank": ("India Post Payments Bank Ltd",),
    "Fino Payments Bank": ("FINO PAYMENTS BANK LIMITED",),
    "NSDL Payments Bank": ("NSDL Payments Bank Limited",),
    "Paytm Payments Bank": ("PAYTM PAYMENTS BANK LIMITED",),
}


def slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")


def main(source_name: str, output_name: str) -> None:
    source = Path(source_name)
    output = Path(output_name)
    workbook = openpyxl.load_workbook(source, read_only=True, data_only=True)
    sheet = workbook.active

    by_entity: dict[str, set[str]] = defaultdict(set)
    for header, entity in sheet.iter_rows(min_row=2, values_only=True):
        if header is None or entity is None:
            continue
        normalized = str(header).strip().upper()
        if re.fullmatch(r"[A-Z0-9]{4,8}", normalized):
            by_entity[str(entity).strip()].add(normalized)

    records = []
    missing = []
    for display_name, entities in BANKS.items():
        headers = sorted({h for entity in entities for h in by_entity.get(entity, set())})
        if not headers:
            missing.append(display_name)
            continue
        records.append(
            {
                "id": slug(display_name),
                "name": display_name,
                "principalEntities": list(entities),
                "headers": headers,
            }
        )

    if missing:
        raise RuntimeError(f"No TRAI headers found for: {', '.join(missing)}")

    payload = {
        "source": "Telecom Regulatory Authority of India",
        "sourceUrl": "https://www.trai.gov.in/node/7411",
        "published": "2020-06-16",
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "banks": sorted(records, key=lambda item: item["name"].casefold()),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(records)} banks and {sum(len(r['headers']) for r in records)} headers")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Expected source XLSX and output JSON paths")
    main(sys.argv[1], sys.argv[2])
