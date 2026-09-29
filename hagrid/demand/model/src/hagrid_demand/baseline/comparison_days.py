"""The comparison days exported for MATSim: the ParcelDemandScenarioGenerator week, moved off public holidays."""

from __future__ import annotations

import datetime as dt

from .calendar import public_holidays

# Fri/Sat of ISO week 19 and Mon-Sat of ISO week 20 (the eight days of the 2025 acceptance runs).
_ISO_DAYS = ((19, 5), (19, 6), (20, 1), (20, 2), (20, 3), (20, 4), (20, 5), (20, 6))


def comparison_days(year: int, region: str | None = "NI") -> list[str]:
    """ISO-week comparison days of *year*; a day on a public holiday moves to the same weekday one week later.

    Ascension Day and Whit Monday fall into ISO weeks 19-20 in some years; a holiday has no deliveries and would
    leave a gap in the exported week, so the day keeps its weekday and shifts by whole weeks until it is open.
    """
    holidays = set(public_holidays(int(year), region))
    days = []
    for week, weekday in _ISO_DAYS:
        day = dt.date.fromisocalendar(int(year), week, weekday)
        while day.isoformat() in holidays:
            day += dt.timedelta(days=7)
        days.append(day.isoformat())
    return days
