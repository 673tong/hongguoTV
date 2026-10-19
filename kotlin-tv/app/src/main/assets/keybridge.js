// 红果 TV 的 wasm 密钥桥。
//
// 站点播放器把内容密钥的派生放在一个 Emscripten 模块（aw.wasm）里：
//   play-info 只给出信封凭证 player_access_key，真正的 AES 内容密钥由
//   _decryptKey(scheme, keyPtr, len, ivSize, outLen, outPtr) 解封后写进
//   wasm 自己的状态，再靠 _descrypt(scheme, dataPtr, len, ivPtr) 就地解密。
// 密钥不落 JS 侧，所以纯 Kotlin 无法复刻，只能原样运行这个 wasm。
//
// 本脚本是一个与站点业务代码无关的薄壳：只实例化 wasm、喂凭证、解数据块。
// 由 CencKeyBridge.kt 通过 evaluateJavascript 调用，输入输出都是 base64。
(function () {
  "use strict";

  var memory = null;
  var api = null;
  // Emscripten 的 stackAlloc 桩需要一个只增不减的栈指针；1MB 起点足够，
  // 因为每次调用结束都会把指针复位。
  var STACK_BASE = 1024 * 1024;
  var stackTop = STACK_BASE;

  function heap8() { return new Uint8Array(memory.buffer); }

  function b64ToBytes(b64) {
    var raw = atob(b64);
    var out = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }

  function bytesToB64(bytes) {
    // 分块转换：String.fromCharCode.apply 在长数组上会爆栈。
    var CHUNK = 8192, parts = [];
    for (var s = 0; s < bytes.length; s += CHUNK) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(s, Math.min(s + CHUNK, bytes.length))));
    }
    return btoa(parts.join(""));
  }

  function alloc(bytes) {
    var p = api.malloc(bytes.length);
    heap8().set(bytes, p);
    return p;
  }

  // aw.wasm 的导入全部是标准 Emscripten 桩，命名空间固定为 "a"。
  function buildImports() {
    return {
      a: {
        a: function (what) { throw new Error("wasm abort: " + what); },
        b: function () { return 0; },              // localtime -> NULL
        c: function () { return 0; },              // environ
        d: function () { return 0; },
        e: function (n) {                          // emscripten_resize_heap
          var cur = memory.buffer.byteLength;
          var want = Math.max(n, cur * 2);
          memory.grow(Math.ceil((want - cur) / 65536));
          return 1;
        },
        f: function (dst, src, len) {              // copyWithin
          heap8().copyWithin(dst, src, src + len);
        },
        g: function (e) { throw e; },              // C++ exception
        h: function (n) {                          // stackAlloc
          var p = stackTop;
          stackTop = (stackTop + n + 15) & ~15;
          return p;
        }
      }
    };
  }

  // 由 Kotlin 传入 aw.wasm 的 base64（asset 里读出来再编码，避免 file:// 的 fetch 限制）。
  window.hgInit = function (wasmBase64) {
    return WebAssembly.instantiate(b64ToBytes(wasmBase64), buildImports()).then(function (res) {
      var ex = res.instance ? res.instance.exports : res.exports;
      memory = ex.i;
      api = { ctors: ex.j, malloc: ex.k, free: ex.l, descrypt: ex.n, decryptKey: ex.p };
      api.ctors();                                 // 触发 C++ 静态构造
      return "ok:" + memory.buffer.byteLength;
    });
  };

  // 喂信封凭证。wasm 会把真正的内容密钥留在自己的状态里。
  //
  // 入参是**已经还原好的**标准 base64 字符串（52 字符）。注意不能在这里再套一层
  // base64：_decryptKey 内部会先按 base64 解码入参，多编一层就变成 70 字节的
  // 错误密文，wasm 解不出密钥还会写越界。所以 Kotlin 侧只做 JS 字符串转义，
  // 不做编码（见 CencKeyBridge#jsLiteral）。
  window.hgSetKey = function (accessKeyBase64) {
    var key = b64ToBytes(accessKeyBase64);
    var keyPtr = alloc(key);
    var outPtr = api.malloc(16);
    try {
      // scheme=0（CTR）、ivSize=16 与站点播放器一致；outLen 传 0，密钥不写回 JS。
      api.decryptKey(0, keyPtr, key.length, 16, 0, outPtr);
    } finally {
      api.free(keyPtr);
      api.free(outPtr);
    }
    return "ok";
  };

  // 对一块密文就地做 AES-CTR。iv 必须是 16 字节（senc 的 IV 补零到 16）。
  window.hgDecrypt = function (dataBase64, ivBase64) {
    var data = b64ToBytes(dataBase64);
    var ivRaw = b64ToBytes(ivBase64);
    var iv = new Uint8Array(16);
    iv.set(ivRaw.subarray(0, Math.min(16, ivRaw.length)));
    var dPtr = alloc(data), ivPtr = alloc(iv), result;
    try {
      api.descrypt(0, dPtr, data.length, ivPtr);
      result = bytesToB64(heap8().subarray(dPtr, dPtr + data.length));
    } finally {
      api.free(dPtr);
      api.free(ivPtr);
    }
    return result;
  };
})();
