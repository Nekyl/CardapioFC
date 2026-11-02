import json
import app

def games_json(date, league=None):
    if league:
        payload = app.load_league_day(date, league)
    else:
        payload = app.load_day(date)
    return json.dumps(payload, ensure_ascii=False)

def live_json(date, league=None):
    return json.dumps(app.refresh_live_matches(app.fetch_fresh_day(date, league, include_channels=True)), ensure_ascii=False)
