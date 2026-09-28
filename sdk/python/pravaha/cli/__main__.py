"""``python -m pravaha.cli``: the ``pravaha`` command without its console script.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from pravaha.cli._app import run

if __name__ == "__main__":
    run()
