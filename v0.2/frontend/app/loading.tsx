export default function Loading() {
  return (
    <div className="grid min-h-screen place-items-center bg-[#f7f8f8]" role="status" aria-label="页面正在加载">
      <div className="h-7 w-7 animate-spin rounded-full border-2 border-[#d5ded9] border-t-[#2f6a4a]" />
    </div>
  );
}
