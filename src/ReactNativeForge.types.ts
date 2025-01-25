export type VideoSize = {
  width: number;
  height: number;
};

export type PaddingResult = {
  outputPath: string;
  ratio: number;
};

export type ReactNativeForgeModuleEvents = {
  onProgress: (event: { progress: number }) => void;

  getAllFrames(
    inputPath: string,
    outputFolder: string,
    duration: number,
    size?: VideoSize
  ): Promise<string[]>;

  getFrame(
    inputPath: string,
    outputPath: string,
    timestamp: number,
    quality?: number,
    size?: VideoSize
  ): Promise<string>;

  padToRatio(
    inputPath: string,
    outputPath: string,
    targetRatio: number
  ): Promise<PaddingResult>;

  getDuration(inputPath: string): Promise<number>;
};
