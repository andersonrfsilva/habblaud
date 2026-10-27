import type { IncomingMessage } from 'node:http';
import { Readable } from 'node:stream';
import { AttachmentStore, type AttachmentConsumer } from '../spawn/attachments';
import { tempDir } from './fixtures';

export function attachmentStore() {
  return new AttachmentStore({ dir: tempDir().dir });
}
export function attachmentRequest(data: Buffer | string, name = 'nota.txt', headers: Record<string, string> = {}) {
  const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const stream = Readable.from([bytes]) as IncomingMessage;
  stream.headers = { 'content-type': 'application/octet-stream', 'x-habblaud-file-name': encodeURIComponent(name), 'x-habblaud-file-type': 'text/plain', 'content-length': String(bytes.length), ...headers };
  return stream;
}
export function uploadAttachment(store: AttachmentStore, data = 'marcador', name = 'nota.txt', consumer: AttachmentConsumer = 'messages') {
  return store.receive(attachmentRequest(data, name), consumer);
}
