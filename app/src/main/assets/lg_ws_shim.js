/* Routes wss:// connections to private LAN IPs through the native bridge.
   LG TVs serve port 3001 with a self-signed certificate, which a WebView
   WebSocket cannot accept. Everything else uses the browser's WebSocket. */
(function () {
  if (!window.LGBridge || window.__lgShimInstalled) return;
  window.__lgShimInstalled = true;
  var Native = window.WebSocket, socks = {}, nextId = 1;

  function isLanWss(u) {
    var m = /^wss:\/\/(\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?(?:[\/?#]|$)/.exec(u);
    if (!m) return false;
    var p = m[1].split('.').map(Number);
    if (p.some(function (n) { return n > 255; })) return false;
    return p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168);
  }

  function LGSocket(url) {
    this.url = String(url); this.readyState = 0; this.protocol = ''; this.extensions = '';
    this.bufferedAmount = 0; this.binaryType = 'blob';
    this.onopen = this.onmessage = this.onerror = this.onclose = null;
    this._l = {}; this._id = nextId++; socks[this._id] = this; window.__lgLastWsError = "";
    LGBridge.open(this._id, this.url);
  }
  LGSocket.CONNECTING = 0; LGSocket.OPEN = 1; LGSocket.CLOSING = 2; LGSocket.CLOSED = 3;
  LGSocket.prototype.CONNECTING = 0; LGSocket.prototype.OPEN = 1;
  LGSocket.prototype.CLOSING = 2; LGSocket.prototype.CLOSED = 3;
  LGSocket.prototype.send = function (d) {
    if (this.readyState !== 1) throw new DOMException('WebSocket is not open', 'InvalidStateError');
    LGBridge.send(this._id, String(d));
  };
  LGSocket.prototype.close = function (code, reason) {
    if (this.readyState >= 2) return;
    this.readyState = 2; LGBridge.close(this._id, code || 1000, reason || '');
  };
  LGSocket.prototype.addEventListener = function (t, f) { (this._l[t] = this._l[t] || []).push(f); };
  LGSocket.prototype.removeEventListener = function (t, f) {
    this._l[t] = (this._l[t] || []).filter(function (x) { return x !== f; });
  };
  LGSocket.prototype._emit = function (t, ev) {
    ev.type = t; ev.target = this;
    var h = this['on' + t]; if (typeof h === 'function') { try { h.call(this, ev); } catch (e) { console.error(e); } }
    (this._l[t] || []).slice().forEach(function (f) { try { f.call(this, ev); } catch (e) { console.error(e); } }, this);
  };

  window.__lgWsEvent = function (id, type, a, b) {
    var s = socks[id]; if (!s) return;
    if (type === 'open') { s.readyState = 1; s._emit('open', {}); }
    else if (type === 'message') { s._emit('message', { data: a }); }
    else if (type === 'error') { window.__lgLastWsError = a || 'connection failed'; s._emit('error', {}); }
    else if (type === 'close') {
      s.readyState = 3; delete socks[id];
      s._emit('close', { code: a, reason: b || '', wasClean: a === 1000 });
    }
  };

  function WS(url, protocols) {
    var u = String(url);
    return isLanWss(u) ? new LGSocket(u) : (protocols === undefined ? new Native(u) : new Native(u, protocols));
  }
  WS.CONNECTING = 0; WS.OPEN = 1; WS.CLOSING = 2; WS.CLOSED = 3;
  WS.prototype = Native.prototype;
  window.WebSocket = WS;
})();
