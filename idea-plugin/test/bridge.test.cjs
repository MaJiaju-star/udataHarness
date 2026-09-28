const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync(`${__dirname}/../src/main/resources/bridge.js`, 'utf8')
  .replace('/*__NATIVE_QUERY__*/', 'sendNative(m, rejectQuery);');

function createBridge(send) {
  const requests = [], events = [], timers = new Map();
  let nextTimer = 0;
  const window = {dispatchEvent: e => events.push(e.type)};
  const context = vm.createContext({
    window, Event: class {constructor(type) {this.type = type;}},
    setTimeout: fn => {const id = ++nextTimer; timers.set(id, fn); return id;},
    clearTimeout: id => timers.delete(id),
    sendNative: (message, reject) => {
      requests.push(message);
      if (message.method === 'ready') {
        window.udataReceive({requestId: message.requestId, result: {ready: true}});
      } else if (send) send(message, reject);
    },
  });
  const inject = () => vm.runInContext(script, context);
  inject();
  return {window, requests, events, timers, inject};
}

test('handshake works without a frontend listener', () => {
  const bridge = createBridge();
  assert.equal(bridge.requests[0].method, 'ready');
  assert.deepEqual(bridge.events, ['udata:ready']);
  assert.equal(bridge.timers.size, 0);
});

test('out-of-order replies reach their own callers and duplicate replies are ignored', () => {
  const bridge = createBridge(), results = [];
  for (const method of ['getSelection', 'getCurrentFile']) {
    bridge.window.udataNative(JSON.stringify({method}), value => results.push([method, JSON.parse(value)]), assert.fail);
  }
  const [, first, second] = bridge.requests;
  bridge.window.udataReceive({requestId: second.requestId, result: {path: 'second.java'}});
  bridge.window.udataReceive({requestId: first.requestId, result: {path: 'first.java'}});
  bridge.window.udataReceive({requestId: first.requestId, result: {path: 'duplicate'}});
  assert.deepEqual(results, [['getCurrentFile', {path: 'second.java'}], ['getSelection', {path: 'first.java'}]]);
  assert.equal(bridge.timers.size, 0);
});

test('repeated injection preserves pending requests', () => {
  const bridge = createBridge();
  let result;
  bridge.window.udataNative('{"method":"openFile"}', value => {result = JSON.parse(value);}, assert.fail);
  const request = bridge.requests.at(-1);
  bridge.inject();
  bridge.window.udataReceive({requestId: request.requestId, result: {opened: true}});
  assert.deepEqual(result, {opened: true});
  assert.equal(bridge.timers.size, 0);
});

test('native errors and rejected queries fail immediately and clear timers', () => {
  for (const send of [
    (_m, reject) => reject(403, 'Untrusted page'),
    () => {throw new Error('Query unavailable');},
  ]) {
    const bridge = createBridge(send), errors = [];
    bridge.window.udataNative('{"method":"openFile"}', assert.fail, (code, message) => errors.push({code, message}));
    assert.equal(errors.length, 1);
    assert.equal(bridge.timers.size, 0);
  }
  const bridge = createBridge();
  let error;
  bridge.window.udataNative('{"method":"openFile"}', assert.fail, (code, message) => {error = {code, message};});
  bridge.window.udataReceive({requestId: bridge.requests.at(-1).requestId, error: '文件不存在'});
  assert.deepEqual(error, {code: 500, message: '文件不存在'});
  assert.equal(bridge.timers.size, 0);
});

test('timeouts reject once and late replies cannot complete a failed request', () => {
  const bridge = createBridge();
  let failures = 0;
  bridge.window.udataNative('{"method":"openFile"}', assert.fail, code => {assert.equal(code, 408); failures++;});
  [...bridge.timers.values()].forEach(fn => fn());
  bridge.window.udataReceive({requestId: bridge.requests.at(-1).requestId, result: {opened: true}});
  assert.equal(failures, 1);
  assert.equal(bridge.timers.size, 0);
});
