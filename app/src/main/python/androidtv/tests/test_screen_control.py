"""Screen-control commands: catalog, validation, and the no-auto-tap guard.

Runs without Android: the fake Native stands in for DeviceBridge, exactly
like test_runtime.py. The Kotlin side (service, gestures, MediaProjection)
cannot be exercised here; see docs/SCREEN_CONTROL.md for the on-device
checklist that covers it.
"""
import json

import pytest

from androidtv.commands import catalog, validate
from androidtv.runtime import Runtime
from androidtv.compat import AndroidExecutor, android_account
from androidtv.events import EventStore


class Native:
    def __init__(self, **settings):
        self.config = settings
        self.calls = []

    def settings(self):
        return json.dumps(self.config)

    def invoke(self, command, params):
        self.calls.append((command, json.loads(params)))
        return json.dumps({'ok': True, 'payload': {'observed': command}})

    def drainEvents(self):
        return json.dumps([])


@pytest.fixture
def runtime(tmp_path):
    runtime = Runtime(AndroidExecutor(android_account(str(tmp_path))), Native())
    yield runtime
    runtime.close()


NEW_COMMANDS = [
    'screen.tap', 'screen.tap_text', 'screen.swipe', 'screen.type',
    'screen.read', 'screen.screenshot', 'screen.back', 'screen.home',
    'app.switch',
]


def test_screen_control_commands_are_in_the_catalog(runtime):
    specs = runtime.specs()
    for name in NEW_COMMANDS:
        assert name in specs, name
    # Exact argument shapes (the contract Muse relies on).
    assert set(specs['screen.tap']['optional']) == {'x', 'y', 'node_index'}
    assert set(specs['screen.tap_text']['required']) == {'text'}
    assert set(specs['screen.swipe']['required']) == {'x1', 'y1', 'x2', 'y2'}
    assert set(specs['screen.swipe']['optional']) == {'duration_ms'}
    assert set(specs['screen.type']['required']) == {'text'}
    assert set(specs['screen.read']['optional']) == {'max_nodes'}
    assert specs['screen.screenshot']['timeout_ms'] == 45000
    assert set(specs['app.switch']['optional']) == {'target', 'action'}
    # Still a normal (non-developer) catalog: no shell appears with them.
    assert 'system.run' not in specs


@pytest.mark.parametrize('command,params', [
    ('screen.tap', {'x': '100', 'y': 200}),
    ('screen.tap', {'x': 100, 'y': 200, 'unknown': 1}),
    ('screen.tap_text', {}),
    ('screen.swipe', {'x1': 1, 'y1': 2, 'x2': 3}),
    ('screen.type', {'text': 42}),
    ('screen.read', {'max_nodes': 'all'}),
    ('app.switch', {'target': 7}),
    ('screen.back', {'extra': True}),
])
def test_invalid_screen_control_requests_never_reach_native(runtime, command, params):
    assert not runtime.run(command, params)['ok']
    assert runtime.native.calls == []


@pytest.mark.parametrize('command,params', [
    ('screen.tap', {'x': 100, 'y': 200}),
    ('screen.tap', {'node_index': 3}),
    ('screen.tap_text', {'text': 'Next'}),
    ('screen.swipe', {'x1': 1, 'y1': 2, 'x2': 3, 'y2': 4}),
    ('screen.type', {'text': 'hello'}),
    ('screen.read', {}),
    ('screen.screenshot', {}),
    ('screen.back', {}),
    ('screen.home', {}),
    ('app.switch', {'target': 'com.example.app'}),
    ('app.switch', {'action': 'recents'}),
])
def test_valid_screen_control_requests_reach_native(runtime, command, params):
    assert runtime.run(command, params)['ok']
    assert runtime.native.calls == [(command, params)]


def test_screen_control_cannot_be_saved_in_a_scene_or_automation(runtime):
    bad = {'id': 'auto-tap', 'actions': [{'command': 'screen.tap', 'params': {'x': 1, 'y': 2}}]}
    assert not runtime.run('automation.put', bad)['ok']
    bad = {'id': 'auto-switch', 'actions': [{'command': 'app.switch', 'params': {'target': 'com.example.app'}}]}
    assert not runtime.run('automation.put', bad)['ok']
    assert runtime.native.calls == []
    # Ordinary scenes still work.
    good = {'id': 'hello', 'actions': [{'command': 'screen.show', 'params': {'text': 'hi'}}]}
    assert runtime.run('automation.put', good)['ok']
    assert runtime.run('automation.delete', {'id': 'hello'})['payload']['deleted']


def test_catalog_validation_matches_runtime_validation():
    specs = catalog(developer=False)
    validate('screen.read', {'max_nodes': 50}, specs)
    with pytest.raises(ValueError):
        validate('screen.read', {'max_nodes': 'fifty'}, specs)
