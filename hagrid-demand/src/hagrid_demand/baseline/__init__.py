"""Deterministic HAGRID demand baseline stages."""
"""Deterministic, auditable reference baseline."""

__all__ = ["render_baseline", "run_baseline"]


def __getattr__(name):
    """Avoid importing the workflow while the common cache imports config constants."""
    if name == "run_baseline":
        from .workflow import run_baseline
        return run_baseline
    if name == "render_baseline":
        from .dashboard import render_baseline
        return render_baseline
    raise AttributeError(name)
