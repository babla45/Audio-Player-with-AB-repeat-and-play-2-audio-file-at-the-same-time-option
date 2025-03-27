function SortSelector({ currentSort, onSortChange }) {
  return (
    <div className="sort-controls">
      <label htmlFor="sort-select">Sort by:</label>
      <select 
        id="sort-select" 
        value={currentSort} 
        onChange={(e) => onSortChange(e.target.value)}
      >
        <option value="name">Name</option>
        <option value="date">Date</option>
        <option value="size">Size</option>
        {/* ... existing options ... */}
      </select>
    </div>
  );
}

export default SortSelector; 