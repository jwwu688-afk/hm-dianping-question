(function () {
  'use strict';

  function createParser(onEvent) {
    let buffer = '';
    let finished = false;

    function emit(frame) {
      const lines = frame.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
      let event = 'message';
      const dataLines = [];
      lines.forEach(line => {
        if (!line || line.charAt(0) === ':') return;
        const separator = line.indexOf(':');
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? '' : line.slice(separator + 1);
        if (value.charAt(0) === ' ') value = value.slice(1);
        if (field === 'event') event = value;
        if (field === 'data') dataLines.push(value);
      });
      if (!dataLines.length) return;
      const raw = dataLines.join('\n');
      let data;
      try {
        data = JSON.parse(raw);
      } catch (_) {
        data = {message: raw};
      }
      onEvent({event, data});
    }

    function drain(allowTrailingFrame) {
      buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (frame) emit(frame);
      }
      if (allowTrailingFrame && buffer) {
        emit(buffer);
        buffer = '';
      }
    }

    return {
      push(chunk) {
        if (finished || !chunk) return;
        buffer += chunk;
        drain(false);
      },
      finish() {
        if (finished) return;
        finished = true;
        drain(true);
      }
    };
  }

  const api = {createParser};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.RagStream = api;
})();
