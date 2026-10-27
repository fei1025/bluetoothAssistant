/** Bluetooth Assistant SDK v1. Injected by the host before local plugin scripts. */
export {};
declare global {
  type BtTransport = 'classic' | 'ble';
  type BtCapability = 'classic.scan' | 'classic.connect' | 'classic.io' | 'ble.scan' | 'ble.connect' | 'ble.gatt';
  type BtJson = null | boolean | number | string | BtJson[] | {[key: string]: BtJson};
  interface BtError extends Error { code: string; requestId: string; }
  interface BtSession {
    transport: BtTransport; sessionId: string; address: string;
    connected: boolean; connecting: boolean; mtu?: number; rssi?: number;
  }
  interface BtHost {
    sdk: 1; runId: string; pluginId: string; language: string;
    capabilities: BtCapability[]; developerMode: boolean; classic: BtSession; ble: BtSession;
  }
  interface BtDevice {
    transport: BtTransport; address: string; name?: string; rssi?: number; bondState?: number;
    connectable?: boolean; serviceUuids?: string[];
    /** Advertising data fields are Base64 strings, independent of the data event's Uint8Array. */
    advertisement?: string; manufacturerData?: Record<string, string>; serviceData?: Record<string, string>;
  }
  interface BtCharacteristic {
    handle: string; uuid: string; instanceId: number; properties: number; descriptors: string[];
  }
  interface BtService { uuid: string; instanceId: number; characteristics: BtCharacteristic[]; }
  interface BtResult {
    status: 'OK' | 'SENT' | 'SENT_UNCONFIRMED'; bytes?: Uint8Array;
    completedBytes?: number; mtu?: number; rssi?: number;
  }
  interface BtEvents {
    device: BtDevice;
    state: BtSession;
    data: {transport: BtTransport; sessionId: string; characteristic?: string; bytes: Uint8Array};
    operationComplete: {method: string; requestId: string; success: boolean};
    error: {code: string; message: string; requestId: string};
    dataLoss: {transport?: BtTransport; sessionId?: string; reason?: string};
  }
  interface BtAssistant {
    ready(): Promise<BtHost>;
    host: {getInfo(): Promise<BtHost>; getState(): Promise<BtHost>};
    storage: {get(key: string): Promise<BtJson>; set(key: string, value: BtJson): Promise<null>; remove(key: string): Promise<null>};
    on<K extends keyof BtEvents>(name: K, handler: (value: BtEvents[K]) => void): () => void;
    classic: {
      paired(): Promise<BtDevice[]>;
      scan(): Promise<{scanning: boolean}>;
      stopScan(): Promise<null>;
      connect(options: {address: string; uuid?: string}): Promise<BtSession>;
      disconnect(): Promise<null>;
      write(options: {sessionId: string; bytes: Uint8Array}): Promise<BtResult>;
    };
    ble: {
      scan(options?: {namePrefix?: string}): Promise<{scanning: boolean}>;
      stopScan(): Promise<null>;
      connect(options: {address: string}): Promise<BtSession>;
      disconnect(): Promise<null>;
      services(options: {sessionId: string}): Promise<BtService[]>;
      read(options: {sessionId: string; characteristic: string}): Promise<BtResult>;
      write(options: {sessionId: string; characteristic: string; bytes: Uint8Array; withoutResponse?: boolean; split?: boolean}): Promise<BtResult>;
      subscribe(options: {sessionId: string; characteristic: string; mode: 0 | 1 | 2}): Promise<BtResult>;
      requestMtu(options: {sessionId: string; mtu: number}): Promise<BtResult>;
      readRssi(options: {sessionId: string}): Promise<BtResult>;
    };
  }
  interface Window { readonly btAssistant: BtAssistant; }
}
