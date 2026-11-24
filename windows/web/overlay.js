self.onmessage = ({data: {bitmap, width, height, clip, peaking}}) => {
  const surface = new OffscreenCanvas(width, height);
  const ctx = surface.getContext('2d', {willReadFrequently:true});
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  const source = ctx.getImageData(0, 0, width, height).data;
  const overlay = ctx.createImageData(width, height);
  const gray = new Uint8Array(width * height);
  for (let p = 0; p < gray.length; p++) {
    gray[p] = (77 * source[4*p] + 150 * source[4*p+1] + 29 * source[4*p+2]) >> 8;
  }
  for (let y = 1; y < height-1; y++) {
    for (let x = 1; x < width-1; x++) {
      const p = y * width + x;
      const value = gray[p];
      if (clip && (value >= 250 || value <= 3)) {
        const color = value >= 250 ? [255,45,45,150] : [50,110,255,150];
        overlay.data.set(color, p*4);
      } else if (peaking && Math.abs(gray[p+1]-gray[p-1])+Math.abs(gray[p+width]-gray[p-width]) > 70) {
        overlay.data.set([40,255,120,170], p*4);
      }
    }
  }
  self.postMessage(overlay, [overlay.data.buffer]);
};
