// Pembuat QR code kecil tanpa dependensi (mode byte, koreksi error M, versi 1-40).
// Dipakai untuk QR sambung HP. Pakai: OrbitQR.draw(canvas, teks, pxPerModul).
(function () {
  'use strict';

  // Tabel per versi (indeks 0 tidak dipakai), level M.
  var ECC_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
  var NUM_BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];

  function rawModules(v) {
    var r = (16 * v + 128) * v + 64;
    if (v >= 2) {
      var n = Math.floor(v / 7) + 2;
      r -= (25 * n - 10) * n - 55;
      if (v >= 7) r -= 36;
    }
    return r;
  }
  function dataCodewords(v) {
    return Math.floor(rawModules(v) / 8) - ECC_PER_BLOCK[v] * NUM_BLOCKS[v];
  }

  // ---- Reed-Solomon di GF(256) ----
  function gfMul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }
  function rsDivisor(deg) {
    var r = [], i, j;
    for (i = 0; i < deg; i++) r.push(0);
    r[deg - 1] = 1;
    var root = 1;
    for (i = 0; i < deg; i++) {
      for (j = 0; j < deg; j++) {
        r[j] = gfMul(r[j], root);
        if (j + 1 < deg) r[j] ^= r[j + 1];
      }
      root = gfMul(root, 2);
    }
    return r;
  }
  function rsRemainder(data, div) {
    var r = div.map(function () { return 0; });
    data.forEach(function (b) {
      var f = b ^ r.shift();
      r.push(0);
      div.forEach(function (c, i) { r[i] ^= gfMul(c, f); });
    });
    return r;
  }

  function utf8(str) {
    return Array.prototype.slice.call(new TextEncoder().encode(str));
  }

  function makeCodewords(bytes, ver) {
    var bits = [];
    function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
    put(4, 4); // mode byte
    put(bytes.length, ver < 10 ? 8 : 16);
    bytes.forEach(function (b) { put(b, 8); });
    var cap = dataCodewords(ver) * 8;
    put(0, Math.min(4, cap - bits.length));
    put(0, (8 - (bits.length % 8)) % 8);
    for (var pad = 0xec; bits.length < cap; pad ^= 0xec ^ 0x11) put(pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var v = 0;
      for (var j = 0; j < 8; j++) v = (v << 1) | bits[i + j];
      data.push(v);
    }
    // bagi jadi blok, tambah ECC, lalu selang-seling
    var nb = NUM_BLOCKS[ver], el = ECC_PER_BLOCK[ver];
    var raw = Math.floor(rawModules(ver) / 8);
    var nShort = nb - (raw % nb), shortLen = Math.floor(raw / nb);
    var div = rsDivisor(el), blocks = [];
    for (var b = 0, k = 0; b < nb; b++) {
      var dl = shortLen - el + (b < nShort ? 0 : 1);
      var dat = data.slice(k, k + dl);
      k += dl;
      var ecc = rsRemainder(dat, div);
      if (b < nShort) dat.push(0);
      blocks.push(dat.concat(ecc));
    }
    var out = [];
    for (var x = 0; x < blocks[0].length; x++) {
      for (var y = 0; y < nb; y++) {
        if (x !== shortLen - el || y >= nShort) out.push(blocks[y][x]);
      }
    }
    return out;
  }

  function alignPositions(ver, size) {
    if (ver === 1) return [];
    var n = Math.floor(ver / 7) + 2;
    var step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (n * 2 - 2)) * 2;
    var r = [6];
    for (var pos = size - 7; r.length < n; pos -= step) r.splice(1, 0, pos);
    return r;
  }

  function build(text) {
    var bytes = utf8(text), ver;
    for (ver = 1; ver <= 40; ver++) {
      var need = 4 + (ver < 10 ? 8 : 16) + bytes.length * 8;
      if (need <= dataCodewords(ver) * 8) break;
    }
    if (ver > 40) throw new Error('Teks terlalu panjang untuk QR');
    var size = ver * 4 + 17, mod = [], fn = [], i, j;
    for (i = 0; i < size; i++) {
      mod.push(new Array(size).fill(false));
      fn.push(new Array(size).fill(false));
    }
    function setFn(x, y, dark) {
      if (x < 0 || y < 0 || x >= size || y >= size) return;
      mod[y][x] = dark;
      fn[y][x] = true;
    }
    // pola timing
    for (i = 0; i < size; i++) { setFn(6, i, i % 2 === 0); setFn(i, 6, i % 2 === 0); }
    // pola sudut
    function finder(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
        var d = Math.max(Math.abs(dx), Math.abs(dy));
        setFn(cx + dx, cy + dy, d !== 2 && d !== 4);
      }
    }
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    // pola alignment
    var ap = alignPositions(ver, size), n = ap.length;
    for (i = 0; i < n; i++) for (j = 0; j < n; j++) {
      if ((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0)) continue;
      for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) {
        setFn(ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
    // bit versi
    if (ver >= 7) {
      var rem = ver;
      for (i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      var vb = (ver << 12) | rem;
      for (i = 0; i < 18; i++) {
        var c = ((vb >>> i) & 1) !== 0, a = size - 11 + (i % 3), b = Math.floor(i / 3);
        setFn(a, b, c); setFn(b, a, c);
      }
    }
    function formatBits(mask) {
      var d = (0 << 3) | mask; // level M = 0
      var r = d;
      for (var q = 0; q < 10; q++) r = (r << 1) ^ ((r >>> 9) * 0x537);
      var bits = ((d << 10) | r) ^ 0x5412;
      function bit(k) { return ((bits >>> k) & 1) !== 0; }
      for (var k = 0; k <= 5; k++) setFn(8, k, bit(k));
      setFn(8, 7, bit(6)); setFn(8, 8, bit(7)); setFn(7, 8, bit(8));
      for (k = 9; k < 15; k++) setFn(14 - k, 8, bit(k));
      for (k = 0; k < 8; k++) setFn(size - 1 - k, 8, bit(k));
      for (k = 8; k < 15; k++) setFn(8, size - 15 + k, bit(k));
      setFn(8, size - 8, true);
    }
    formatBits(0); // tandai area format sebagai fungsi dulu

    // taruh data zig-zag
    var cw = makeCodewords(bytes, ver), idx = 0;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        for (j = 0; j < 2; j++) {
          var x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
          if (!fn[y][x] && idx < cw.length * 8) {
            mod[y][x] = ((cw[idx >>> 3] >>> (7 - (idx & 7))) & 1) !== 0;
            idx++;
          }
        }
      }
    }

    var MASKS = [
      function (x, y) { return (x + y) % 2 === 0; },
      function (x, y) { return y % 2 === 0; },
      function (x) { return x % 3 === 0; },
      function (x, y) { return (x + y) % 3 === 0; },
      function (x, y) { return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; },
      function (x, y) { return ((x * y) % 2) + ((x * y) % 3) === 0; },
      function (x, y) { return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; },
      function (x, y) { return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; }
    ];
    function applyMask(m) {
      for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
        if (!fn[y][x] && MASKS[m](x, y)) mod[y][x] = !mod[y][x];
      }
    }
    function penalty() {
      var p = 0, y, x, run, dark = 0;
      for (y = 0; y < size; y++) {
        run = 1;
        for (x = 1; x <= size; x++) {
          if (x < size && mod[y][x] === mod[y][x - 1]) run++;
          else { if (run >= 5) p += run - 2; run = 1; }
        }
      }
      for (x = 0; x < size; x++) {
        run = 1;
        for (y = 1; y <= size; y++) {
          if (y < size && mod[y][x] === mod[y - 1][x]) run++;
          else { if (run >= 5) p += run - 2; run = 1; }
        }
      }
      for (y = 0; y < size - 1; y++) for (x = 0; x < size - 1; x++) {
        var c = mod[y][x];
        if (c === mod[y][x + 1] && c === mod[y + 1][x] && c === mod[y + 1][x + 1]) p += 3;
      }
      var re = /(?=00001011101|10111010000)/g;
      for (y = 0; y < size; y++) {
        var row = '', col = '';
        for (x = 0; x < size; x++) { row += mod[y][x] ? '1' : '0'; col += mod[x][y] ? '1' : '0'; }
        p += 40 * ((('0000' + row + '0000').match(re) || []).length + (('0000' + col + '0000').match(re) || []).length);
      }
      for (y = 0; y < size; y++) for (x = 0; x < size; x++) if (mod[y][x]) dark++;
      var total = size * size;
      p += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
      return p;
    }
    var best = 0, bestP = Infinity;
    for (var m = 0; m < 8; m++) {
      applyMask(m); formatBits(m);
      var pp = penalty();
      if (pp < bestP) { bestP = pp; best = m; }
      applyMask(m); // balikkan
    }
    applyMask(best); formatBits(best);
    return { size: size, mod: mod, version: ver };
  }

  function draw(canvas, text, px) {
    var q = build(text), quiet = 4, s = q.size + quiet * 2;
    px = px || 6;
    canvas.width = canvas.height = s * px;
    var g = canvas.getContext('2d');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, canvas.width, canvas.height);
    g.fillStyle = '#000';
    for (var y = 0; y < q.size; y++) for (var x = 0; x < q.size; x++) {
      if (q.mod[y][x]) g.fillRect((x + quiet) * px, (y + quiet) * px, px, px);
    }
    return q.version;
  }

  window.OrbitQR = { build: build, draw: draw };
})();
