ALTER TABLE products
    ADD FULLTEXT INDEX ft_products_name_brand (name, brand)
    WITH PARSER ngram;
